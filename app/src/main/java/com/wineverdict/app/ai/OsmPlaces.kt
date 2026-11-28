package com.wineverdict.app.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * The town's wine places, from OpenStreetMap rather than from a search engine.
 *
 * A place question used to depend on one keyless search endpoint, and that endpoint refuses a burst
 * with an anomaly page — measured twice on the machine that built this. The map answers the same
 * question from a different angle and is not a search engine at all: every bar, restaurant, wine
 * shop and winery tasting room carries a name, an address, and often its own website and hours.
 *
 * Two map services are used, and the order was measured rather than assumed:
 *
 *  - [Nominatim] is the first: asked for "wine bar" inside the town's own bounding box it returned
 *    eleven real bars — Stem Wine Bar PDX, The Portland Wine Bar, Southeast Wine Collective — each
 *    with "Portland, Oregon" in its address, and websites and hours on most. It answered in under a
 *    second and it is built for this shape of question.
 *  - Overpass is the second: richer when it answers (it also finds bars whose *name* says wine), but
 *    its public instances spend minutes at capacity — 502 "at capacity" for one, 34–45 second waits
 *    for another, and an IP rate-limit on the third after a handful of queries. It is a good second
 *    opinion and a bad single point of failure.
 *
 * What neither can do is say a place is *good*, and both are stale about closures — which is why the
 * research does not stop here: each venue's own page is fetched as the evidence, and a closure on
 * that page removes it. The address is what lets a Portland, Oregon question rule out Portland,
 * Connecticut before the model is ever asked.
 */
object OsmPlaces {

    /**
     * The service this app asks, and the instances it falls back to.
     *
     * Mutable so a live run can point them at a host that refuses connections and prove the
     * cannot-confirm reply on a real failure instead of a simulated one. Nothing in the app sets
     * them: the defaults are the only values a user ever gets.
     */
    @Volatile
    var nominatim: String = "https://nominatim.openstreetmap.org/search"

    @Volatile
    var overpass: List<String> = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.openstreetmap.fr/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter"
    )

    /** Map requests made since [resetRequests]; the live run prints it per question. */
    @Volatile
    var requests: Int = 0
        private set

    fun resetRequests() {
        requests = 0
    }

    /** Ask for the map, not a person: both services' usage policies want an identifiable client. */
    private const val AGENT = "wine-verdict/1.2 (Android cellar app; place research)"

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        // A slow POST behind a proxy answers badly over HTTP/2 streams; one plain HTTP/1.1
        // connection per request is the shape that was measured working.
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    private val media = "text/plain; charset=utf-8".toMediaType()

    /** One place the map holds, with the address that proves which town it is in. */
    data class Place(
        val name: String,
        val city: String = "",
        val region: String = "",
        val street: String = "",
        val website: String = "",
        val hours: String = "",
        val kind: String = ""
    ) {
        /** "526 SW Yamhill St, Portland, OR" — the address as the map prints it, without guessing. */
        val address: String
            get() = listOf(street, city, region)
                .filter { it.isNotBlank() }
                .joinToString(", ")
    }

    /** A map place as the research's venue, with the address that proves the town. */
    fun asVenue(place: Place): PlaceScout.Venue = PlaceScout.Venue(
        name = place.name,
        url = place.website,
        title = place.name,
        snippet = listOf(place.kind, place.hours).filter { it.isNotBlank() }.joinToString(" · "),
        address = place.address,
        hours = place.hours
    )

    // ---------------------------------------------------------------- Nominatim

    /**
     * The words asked of the map, by kind of outing.
     *
     * Nominatim matches names, so "wine bar" finds the places that call themselves one and "winery"
     * finds the tasting rooms. Three queries is the whole budget: a fourth has never added a place
     * that survived the page check, and every query is a request to a service that asks for one a
     * second.
     */
    fun terms(kind: PlaceScout.Ask.Kind): List<String> = when (kind) {
        PlaceScout.Ask.Kind.SHOP -> listOf("wine shop", "wine")
        PlaceScout.Ask.Kind.BAR -> listOf("wine bar", "wine")
        PlaceScout.Ask.Kind.RESTAURANT,
        PlaceScout.Ask.Kind.ANY -> listOf("wine bar", "winery", "wine")
    }

    /** The URL that lists the towns by this name, with the state that tells them apart. */
    fun townUrl(where: String): String =
        "$nominatim?q=${encode(where)}&format=jsonv2&addressdetails=1&featureType=settlement&limit=12"

    /** The URL that finds [term] inside a town's box, with the tags the evidence needs. */
    fun searchUrl(term: String, box: String): String =
        "$nominatim?q=${encode(term)}&format=jsonv2&addressdetails=1&extratags=1&limit=40" +
            "&bounded=1&viewbox=${encode(box)}"

    /**
     * The towns in a Nominatim settlement reply: name, state, country, and the box to search in.
     *
     * One live query for "Portland" returned twelve settlements in five countries, and "Springfield"
     * returned twelve in the United States alone. That is the data the settling decision needs, and
     * it arrives in the same request that used to fetch only the first town's box — so asking which
     * town a name means costs no extra request.
     */
    fun parseTowns(body: String): List<PlaceScout.Town> {
        if (body.isBlank()) return emptyList()
        val array = runCatching { Json.parseToJsonElement(body) as JsonArray }.getOrNull()
            ?: return emptyList()
        return array.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val name = item.text("name")
            if (name.isBlank()) return@mapNotNull null
            val box = runCatching { item["boundingbox"] as JsonArray }.getOrNull()
                ?: return@mapNotNull null
            val values = box.mapNotNull { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
            if (values.size != 4) return@mapNotNull null
            val (south, north, west, east) = values
            val address = item["address"] as? JsonObject
            PlaceScout.Town(
                name = name,
                state = address?.text("state").orEmpty(),
                country = address?.text("country").orEmpty(),
                countryCode = address?.text("country_code").orEmpty(),
                box = "$west,$north,$east,$south"
            )
        }.distinctBy { PlaceScout.fold(it.name) to PlaceScout.fold(it.state) }
    }

    /**
     * The towns the map knows by this name.
     *
     * A refusal throws rather than returning an empty list: "the map did not answer" and "the map
     * knows no Portland" are different answers, and only the second is worth wording as an empty
     * result. A live run pointed at a dead host must say it could not check, not that the town does
     * not exist.
     */
    suspend fun towns(query: String): List<PlaceScout.Town> = withContext(Dispatchers.IO) {
        parseTowns(getOrThrow(townUrl(query)))
    }

    /**
     * The town's box, in Nominatim's viewbox order (west, north, east, south), or null when the
     * town did not resolve or resolved to another region than the one asked about.
     *
     * The region check is the point: "Portland" resolves to Portland, Oregon when Oregon is asked
     * for, and to Portland, Maine when nothing is. A box for the wrong Portland would put the whole
     * question in the wrong state, so a mismatch is no box at all.
     */
    fun townBox(body: String, region: String): String? =
        parseTowns(body).firstOrNull { regionMatches(it.state, region) }?.box

    /**
     * The places in a Nominatim reply that are in the asked town and region, wine places only.
     *
     * The category is a real filter here: "wine" also matches Portland Wine Storage (a company) and
     * Henry's Wine Vault (a culture venue), and neither is somewhere to have dinner. A missing city
     * is allowed through — the venue's own page still has to name the town — but another state is
     * not, which is what keeps Vancouver, Washington out of a Portland, Oregon answer.
     */
    fun parseNominatim(body: String, town: String, region: String): List<Place> {
        if (body.isBlank()) return emptyList()
        val array = runCatching { Json.parseToJsonElement(body) as JsonArray }.getOrNull()
            ?: return emptyList()
        val wantedTown = PlaceScout.fold(town)
        val seen = LinkedHashMap<String, Place>()
        for (element in array) {
            val item = element as? JsonObject ?: continue
            val name = item.text("name")
            if (name.isBlank()) continue
            val address = item["address"] as? JsonObject
            val city = address?.text("city").orEmpty()
                .ifBlank { address?.text("town").orEmpty() }
                .ifBlank { address?.text("village").orEmpty() }
            val state = address?.text("state").orEmpty()
            if (wantedTown.isNotBlank() && city.isNotBlank() && !PlaceScout.fold(city).contains(wantedTown)) {
                continue
            }
            if (!regionMatches(state, region)) continue
            val category = item.text("category")
            if (category !in WINE_CATEGORIES) continue
            val extra = item["extratags"] as? JsonObject
            val website = extra?.text("website").orEmpty()
                .ifBlank { extra?.text("contact:website").orEmpty() }
                .ifBlank { extra?.text("url").orEmpty() }
            if (!website.startsWith("http")) continue
            val street = listOf(address?.text("house_number").orEmpty(), address?.text("road").orEmpty())
                .filter { it.isNotBlank() }
                .joinToString(" ")
            val place = Place(
                name = name,
                city = city,
                region = state,
                street = street,
                website = website,
                hours = extra?.text("opening_hours").orEmpty(),
                kind = listOf(category, item.text("type")).filter { it.isNotBlank() }.joinToString("/")
            )
            seen.putIfAbsent(PlaceScout.fold(name), place)
        }
        return seen.values.toList()
    }

    /**
     * The town's wine places, or an exception a caller can honestly report as "busy".
     *
     * Nominatim first because it was measured answering in under a second; Overpass second because
     * when its instances have room it also finds the bars whose name says wine. Only when neither
     * produces a place does the question become "cannot check right now".
     */
    suspend fun find(ask: PlaceScout.Ask, box: String = ""): List<Place> =
        withContext(Dispatchers.IO) {
            val town = ask.where.substringBefore(',').trim()
            val region = ask.where.substringAfter(',', "").trim()
            // Settling already found the town's box, so the usual question pays for one town lookup
            // and three term searches. Only a question that arrived with its own box missing pays
            // for a lookup here.
            val known = box.ifBlank {
                townBox(get(townUrl(ask.where)).orEmpty(), region).orEmpty()
            }
            val viaNominatim = runCatching { nominatim(town, region, ask.kind, known) }
                .getOrNull().orEmpty()
            if (viaNominatim.isNotEmpty()) return@withContext viaNominatim
            overpass(ask)
        }

    private suspend fun nominatim(
        town: String,
        region: String,
        kind: PlaceScout.Ask.Kind,
        box: String
    ): List<Place> {
        if (box.isBlank()) return emptyList()
        val found = LinkedHashMap<String, Place>()
        for (term in terms(kind)) {
            val body = get(searchUrl(term, box)) ?: continue
            parseNominatim(body, town, region).forEach { found.putIfAbsent(PlaceScout.fold(it.name), it) }
            // Nominatim's policy asks for at most one request a second; the pause is the price of
            // not being the client that gets the app's users blocked.
            delay(NOMINATIM_PAUSE_MS)
        }
        return found.values.toList()
    }

    /** One Nominatim call, or a retryable refusal that says the map did not answer. */
    private suspend fun getOrThrow(url: String): String {
        var failure: Throwable? = null
        repeat(2) { attempt ->
            if (attempt > 0) delay(RETRY_PAUSE_MS)
            requests++
            try {
                val request = Request.Builder().url(url).addHeader("User-Agent", AGENT).get().build()
                http.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (response.isSuccessful && body.trimStart().startsWith("[")) return body
                    failure = IllegalStateException("HTTP ${response.code}: ${body.take(100)}")
                }
            } catch (e: Exception) {
                failure = e
            }
        }
        throw AiException(BUSY, retryable = true).apply { initCause(failure) }
    }

    /** A Nominatim call whose refusal is tolerable, because Overpass is asked next. */
    private suspend fun get(url: String): String? =
        runCatching { getOrThrow(url) }.getOrNull()

    // ---------------------------------------------------------------- Overpass

    /**
     * The instances tried, in order, for one question.
     *
     * The German server is the reference instance and the fastest when it has room; when its queue
     * is full it answers 502 "at capacity" — measured, for minutes at a time — and the French
     * instance answered the same query with the same 34 Portland places. After a few queries the
     * French instance asks for "white-listed usages", so it is asked second, not first.
     */


    /**
     * The Overpass query for one town.
     *
     * The town is matched by its boundary rather than by a radius: "Portland" is a city with an
     * admin_level of 8, and its boundary is the honest edge of the question. The region is not
     * filtered in the query — Overpass would match every Portland — and is enforced in [parse],
     * where a wrong state can actually be seen.
     */
    fun query(town: String, kind: PlaceScout.Ask.Kind): String {
        val clean = town.replace(Regex("[\\\\\"\\[\\]{}]"), " ").trim()
        val food = if (kind == PlaceScout.Ask.Kind.SHOP) {
            ""
        } else {
            """
              nwr["amenity"~"bar|pub|restaurant"]["name"~"[Ww]ine",i](area.a);
              nwr["amenity"="bar"]["cuisine"~"wine",i](area.a);
            """
        }
        return """
        [out:json][timeout:25];
        area["name"="$clean"]["admin_level"="8"]->.a;
        (
          nwr["shop"="wine"](area.a);
          nwr["craft"="winery"](area.a);
          $food
        );
        out tags center 80;
        """.trimIndent()
    }

    /** The places in an Overpass reply that are in the asked town and region. */
    fun parse(body: String, town: String, region: String): List<Place> {
        val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: return emptyList()
        val elements = root["elements"] as? JsonArray ?: return emptyList()
        val wantedTown = PlaceScout.fold(town)
        val seen = LinkedHashMap<String, Place>()
        for (element in elements) {
            val tags = (element as? JsonObject)?.get("tags") as? JsonObject ?: continue
            val name = tags.text("name")
            if (name.isBlank()) continue
            val website = tags.text("website").ifBlank { tags.text("contact:website") }
            if (!website.startsWith("http")) continue
            val city = tags.text("addr:city")
            val state = tags.text("addr:state")
            if (wantedTown.isNotBlank() && city.isNotBlank() && !PlaceScout.fold(city).contains(wantedTown)) {
                continue
            }
            if (!regionMatches(state, region)) continue
            val street = listOf(tags.text("addr:housenumber"), tags.text("addr:street"))
                .filter { it.isNotBlank() }
                .joinToString(" ")
            val place = Place(
                name = name.trim(),
                city = city,
                region = state,
                street = street,
                website = website.trim(),
                hours = tags.text("opening_hours"),
                kind = tags.text("amenity").ifBlank { tags.text("shop") }
                    .ifBlank { tags.text("craft") }
            )
            seen.putIfAbsent(PlaceScout.fold(name), place)
        }
        return seen.values.toList()
    }

    private suspend fun overpass(ask: PlaceScout.Ask): List<Place> {
        val body = post(query(ask.where.substringBefore(',').trim(), ask.kind))
        return parse(
            body = body,
            town = ask.where.substringBefore(',').trim(),
            region = ask.where.substringAfter(',', "").trim()
        )
    }

    /**
     * One POST, with one pass over the instances inside a budget.
     *
     * The service answers 502 with a page that says it is at capacity, and that is a "try again",
     * not an empty town. When every instance refuses, the failure detail is kept as suppressed
     * exceptions so a live run can say which one failed and how — a user is told only [BUSY].
     */
    private suspend fun post(ql: String): String {
        val failures = mutableListOf<Throwable>()
        val deadline = System.currentTimeMillis() + BUDGET_MS
        for (round in 0 until ROUNDS) {
            if (round > 0) delay(RETRY_PAUSE_MS)
            for (host in overpass) {
                if (System.currentTimeMillis() >= deadline) break
                val short = host.substringAfter("//").substringBefore('/')
                requests++
                try {
                    val request = Request.Builder()
                        .url(host)
                        .addHeader("User-Agent", AGENT)
                        .post(ql.toRequestBody(media))
                        .build()
                    http.newCall(request).execute().use { response ->
                        val body = response.body?.string().orEmpty()
                        if (response.isSuccessful && body.trimStart().startsWith("{")) return body
                        failures += IllegalStateException("$short HTTP ${response.code}: ${body.take(100)}")
                    }
                } catch (e: Exception) {
                    failures += IllegalStateException("$short: $e")
                }
            }
        }
        val busy = AiException(BUSY, retryable = true)
        failures.forEach { busy.addSuppressed(it) }
        throw busy
    }

    /** What a user is told when neither map source will answer: a retry, not a guess. */
    // The apology and the retry belong to [PlaceScout.cannotConfirm], which wraps this reason; the
    // first live run said "nothing was guessed, ask again" twice because both said it.
    const val BUSY = "The place map is busy right now and did not answer."

    /**
     * Whether the map's state field is the region that was asked about.
     *
     * OSM stores "OR", a question says "Oregon", and a comparison that insisted on equality would
     * reject every venue in the state. A blank state is not a contradiction (the venue's own page
     * still has to name the town), but a different one is.
     */
    fun regionMatches(state: String, region: String): Boolean {
        val s = PlaceScout.fold(state)
        val r = PlaceScout.fold(region)
        if (s.isBlank() || r.isBlank()) return true
        if (s == r) return true
        if (s.length == 2 && r.startsWith(s)) return true
        if (r.length == 2 && s.startsWith(r)) return true
        return s.startsWith(r) || r.startsWith(s)
    }

    /** The categories that can be a place to sit down or buy wine: a company or a museum cannot. */
    private val WINE_CATEGORIES = setOf("amenity", "shop", "craft")

    /** Nominatim asks for one request a second; this keeps a question inside that. */
    private const val NOMINATIM_PAUSE_MS = 1_100L

    /** How long one question may spend asking the map, across every instance. */
    private const val BUDGET_MS = 40_000L

    /** One pass over the instances: a second costs more waiting than it has ever bought. */
    private const val ROUNDS = 1
    private const val RETRY_PAUSE_MS = 1_500L

    private fun encode(text: String): String = URLEncoder.encode(text, "UTF-8")

    private fun JsonObject.text(key: String): String =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()?.trim().orEmpty()
}
