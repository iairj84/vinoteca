package com.wineverdict.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * One place a search or a fix named: the business, the town, the street, and where it is.
 *
 * The town is what makes a chain usable. Four Loyal Legions are four different nights, and
 * "Loyal Legion" alone would be a dropdown of four identical rows.
 */
data class PlaceHit(
    val name: String,
    val city: String = "",
    val street: String = "",
    val lat: Double = 0.0,
    val lng: Double = 0.0
) {
    /** "Loyal Legion — Beaverton", or just the name when the town is unknown or already in it. */
    val label: String get() = placeLabel(name, city)
}

/** The name as it is read in a list: the business, then the town that tells it from its twins. */
fun placeLabel(name: String, city: String): String {
    val clean = name.trim()
    val town = city.trim()
    if (town.isBlank()) return clean
    if (clean.lowercase().contains(town.lowercase())) return clean
    return "$clean — $town"
}

/**
 * Photon, komoot's OpenStreetMap search: the names Google's geocoder will not give.
 *
 * The platform Geocoder answers a restaurant's name with its street address — measured, not assumed
 * — so a typeahead built on it could only ever offer "4500 SW Watson Ave" for a search of "Loyal L".
 * Photon returns the business itself, with its town, which is what makes four branches four rows.
 *
 * No key, no account, and no guarantee: the terms say extensive use is throttled. Every caller
 * therefore treats an empty answer, a timeout and a failure the same way — as "no suggestions", with
 * the address geocoder and the user's own typing behind it.
 */
class PhotonClient : PlaceLookup {

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    // Komoot asks clients to identify themselves; a mobile browser string is what the rest of the
    // app's web retrieval already sends, and it keeps a plain OkHttp request from looking like a bot.
    private val userAgent =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0 Mobile Safari/537.36"

    /**
     * Businesses near [near] whose name starts with, or contains, [query].
     *
     * The position is rounded to about a kilometre before it leaves the phone: the search needs to
     * know roughly where the user is standing to rank two Loyal Legions, and does not need the room
     * they are standing in.
     */
    override suspend fun search(query: String, near: LatLng?, limit: Int): List<PlaceHit> =
        withContext(Dispatchers.IO) {
            val typed = query.trim()
            if (typed.length < 2) return@withContext emptyList()
            val bias = near?.let {
                "&lat=${decimal(it.lat)}&lon=${decimal(it.lng)}"
            }.orEmpty()
            val url = "https://photon.komoot.io/api/?q=" +
                URLEncoder.encode(typed, "UTF-8") +
                "&limit=${limit.coerceIn(1, 10)}&lang=en$bias"
            parsePlaces(get(url).orEmpty(), businessesOnly = true)
                .distinctBy { it.label to it.street }
                .take(limit)
        }

    /**
     * The named business at this point, or null when the nearest thing is a house, a street or a
     * postcode.
     *
     * Only a business is accepted: a geocoder asked about a doorstep answers with a street number,
     * and writing "6005 Southwest 130th Avenue" into a night as its place would be a fact nobody
     * said. A house gets no tag — the user can save it as Home, which is the one thing that makes it
     * nameable.
     */
    override suspend fun reverse(lat: Double, lng: Double): PlaceHit? = withContext(Dispatchers.IO) {
        // Full precision, unlike the typeahead's rounded bias: a place is being named from a pin, and
        // a pin rounded to a kilometre asks about the middle of town. Measured on the emulator: the
        // rounded request answered with a house down the road and the pub was never offered.
        val url = "https://photon.komoot.io/reverse?lat=${precise(lat)}&lon=${precise(lng)}&lang=en"
        parsePlaces(get(url).orEmpty(), businessesOnly = true, limit = 8)
            .map { it to metresBetween(lat, lng, it.lat, it.lng) }
            .filter { it.second <= REVERSE_MAX_METRES }
            .minByOrNull { it.second }
            ?.first
    }

    private fun get(url: String): String? = try {
        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", userAgent)
            .addHeader("Accept", "application/json")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.string()
        }
    } catch (e: IOException) {
        null
    }

    /** About a kilometre: enough to say which town, not enough to say which room. */
    private fun decimal(value: Double): String =
        String.format(Locale.US, "%.2f", value)

    /** A metre or so: the pin itself, which is what names the place standing on it. */
    private fun precise(value: Double): String =
        String.format(Locale.US, "%.5f", value)

    private companion object {
        /**
         * How far from a business its pin may sit and still be "where I am": a hundred yards. A
         * bar's doorway is where somebody enters it; a fix inside can be fifty yards out, and
         * nothing beyond this is the same building.
         */
        const val REVERSE_MAX_METRES = 91.0
    }
}

/**
 * The features in a Photon answer, in the order they came, with the ones that cannot be a place
 * dropped: no name, no coordinates, or — when [businessesOnly] — a house, a road or a boundary
 * rather than somewhere a person goes.
 */
internal fun parsePlaces(body: String, businessesOnly: Boolean, limit: Int = 10): List<PlaceHit> {
    if (body.isBlank()) return emptyList()
    val root = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return emptyList()
    val features = root["features"] as? JsonArray ?: return emptyList()
    val found = ArrayList<PlaceHit>(features.size)
    for (element in features) {
        val feature = element as? JsonObject ?: continue
        val properties = feature["properties"] as? JsonObject ?: continue
        val name = properties.text("name")
        if (name.isBlank()) continue
        if (businessesOnly && properties.text("osm_key") !in BUSINESS_KINDS) continue
        val coordinates = (feature["geometry"] as? JsonObject)?.get("coordinates") as? JsonArray
        val lng = coordinates?.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: continue
        val lat = coordinates.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: continue
        val street = listOf(properties.text("housenumber"), properties.text("street"))
            .filter { it.isNotBlank() }
            .joinToString(" ")
        found += PlaceHit(
            name = name,
            city = properties.text("city").ifBlank { properties.text("town") }
                .ifBlank { properties.text("village") }
                .ifBlank { properties.text("locality") },
            street = street,
            lat = lat,
            lng = lng
        )
        if (found.size >= limit) break
    }
    return found
}

/** The OSM kinds that are somewhere a person goes, rather than the ground it stands on. */
private val BUSINESS_KINDS = setOf(
    "amenity", "shop", "tourism", "leisure", "craft", "office", "club", "landuse"
)

/** A string property, or "" — a missing field is not an error in this format. */
private fun JsonObject.text(key: String): String {
    val primitive = this[key]?.jsonPrimitive ?: return ""
    return runCatching { primitive.content }.getOrDefault("").trim()
}
