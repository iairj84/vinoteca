package com.wineverdict.app.ai

import com.wineverdict.app.data.Region
import com.wineverdict.app.data.SettingsStore
import com.wineverdict.app.model.ChatMessage
import com.wineverdict.app.model.Wine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

class AiException(
    message: String,
    val retryable: Boolean = false,
    val retryAfterMs: Long? = null,
    /** True when retrying cannot help: a rejected key, an empty account, nothing configured. */
    val fatal: Boolean = false
) : Exception(message)

/** How hard the model may think before answering. Mapped per provider. */
enum class Thinking { OFF, LOW, MEDIUM }

/**
 * What a request is for.
 *
 * It decides two things a caller should not have to assemble: which model field answers, and which
 * provider leads. The advice call and the chat want different models and different orders, because
 * one is a single large reasoning step and the other sends the whole conversation every turn.
 */
enum class AiJob { READ, ADVICE, CHAT }

/**
 * Everything one lookup read about one bottle, with the pages it read.
 *
 * One search and one extraction answer all of it — the note, its flavour words, the ABV, the
 * drinking window, the place and the grapes — so a bottle is asked about once however many facts it
 * is missing. A field no page stated stays null or empty: the app never fills a gap from what a
 * grape usually does.
 */
data class SourcedNotes(
    val notes: String,
    val tags: List<String>,
    val sources: List<String>,
    val caveat: String = "",
    /** The ABV a page printed for this bottling, when one did. Never estimated. */
    val alcohol: Double? = null,
    /** The first year the pages say it is ready. Null when no page gives a window. */
    val drinkFrom: Int? = null,
    /** The last year they say it stays good. Null when no page gives one. */
    val drinkTo: Int? = null,
    val country: String = "",
    val region: String = "",
    val appellation: String = "",
    val grapes: List<String> = emptyList()
)

/**
 * One client for every OpenAI-compatible provider Wine Verdict can use.
 *
 * A request with no explicit provider walks the providers [AiProvider.order] puts in front for its
 * job — primary first for a scan, Groq for the advice call, Gemini for the chat — and returns the
 * first answer. A caller that names a provider (the scan queue does, so it can spread frames across
 * several) gets exactly that one, and handles the retry itself.
 */
class AiClient(
    private val settings: SettingsStore,
    val limiter: RateLimiter = RateLimiter()
) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        // Backstop only: the screen bounds every read well below this and cancels the call.
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private val web = WebLookup()

    /** One connection pool for grounded discovery, however often the key is re-read. */
    private val groundingTransport = HttpTransport()

    /** How long one town's map answer and pages are reused: an evening, not a month. */
    private val PLACE_CACHE_MS = 20 * 60_000L

    private val placeCache = PlaceScout.Cache<List<PlaceScout.Venue>>(PLACE_CACHE_MS)

    private val pageCache = PlaceScout.Cache<String>(PLACE_CACHE_MS)

    /** The towns one town over, cached with the town that had nothing: same evening, same answer. */
    private val nearbyCache = PlaceScout.Cache<List<PlaceScout.Ask>>(PLACE_CACHE_MS)

    suspend fun complete(
        system: String,
        turns: List<ChatMessage>,
        imageBase64: String? = null,
        imagesBase64: List<String> = emptyList(),
        vision: Boolean = false,
        /** What the request is for: it decides the model and who answers. */
        job: AiJob = AiJob.READ,
        jsonMode: Boolean = false,
        thinking: Thinking = Thinking.OFF,
        temperature: Double = 0.4,
        maxTokens: Int = 1400,
        provider: AiProvider? = null,
        model: String? = null,
        onBudgetWait: ((Long) -> Unit)? = null,
        /** Reports who actually answered, after the fallback walk has settled. */
        onAnswer: ((AiProvider, String) -> Unit)? = null,
        /**
         * Reports each provider and model as its request goes out, so a call that fails everywhere
         * can still be shown as "asked Gemini · …" instead of "no provider": who was asked is a
         * fact about the request, and the panel should not lose it just because nobody answered.
         */
        onTry: ((AiProvider, String) -> Unit)? = null
    ): String = withContext(Dispatchers.IO) {
        val images = buildList {
            imageBase64?.takeIf { it.isNotBlank() }?.let { add(it) }
            imagesBase64.filter { it.isNotBlank() }.forEach { add(it) }
        }
        // The month's money is checked before anything is built: a cap that only warns is a
        // suggestion, and this one is meant to hold a dollar. An uncapped meter (limit 0) never
        // blocks, so a user who wants no cap is not punished for it.
        val spent = settings.spend.total()
        if (spent.blocked) {
            throw AiException(
                "This month's spend limit is used: ${spent.spent} of ${spent.limit}. Nothing was " +
                    "sent. Raise the limit in Settings, or it starts again on " +
                    "${spent.month.plusMonths(1)}.",
                fatal = true
            )
        }

        val targets = resolveTargets(provider, model, vision, job)
        if (targets.isEmpty()) {
            throw AiException(
                "Add an API key in Settings. Groq and Google Gemini both have free tiers and either one is enough.",
                fatal = true
            )
        }

        // Every provider's failure is kept: the reason the second one failed can be the only
        // actionable one (a bad key, a model that does not exist), and dropping it sends the
        // reader looking at the wrong thing.
        val failures = mutableListOf<Pair<Target, Exception>>()
        var allRetryable = true
        targets.forEach { target ->
            try {
                onTry?.invoke(target.provider, target.model)
                val answer = attempt(
                    target = target,
                    system = system,
                    turns = turns,
                    images = images,
                    jsonMode = jsonMode,
                    thinking = thinking,
                    temperature = temperature,
                    maxTokens = maxTokens,
                    onBudgetWait = onBudgetWait
                )
                onAnswer?.invoke(target.provider, target.model)
                return@withContext answer
            } catch (e: AiException) {
                failures += target to e
                allRetryable = allRetryable && e.retryable
            } catch (e: Exception) {
                failures += target to e
                allRetryable = false
            }
        }

        val error = failures.firstOrNull()?.second ?: AiException("No provider answered.")
        val message = buildString {
            if (failures.size <= 1) {
                append(error.message.orEmpty())
            } else {
                failures.forEachIndexed { index, (target, failure) ->
                    if (index > 0) append('\n')
                    append(target.provider.shortLabel).append(": ").append(failure.message.orEmpty())
                }
                val tried = targets.map { it.provider.shortLabel }.distinct().joinToString(" and ")
                append("\n\nTried ").append(tried).append('.')
            }
            networkNote(failures.map { it.second })?.let { append("\n\n").append(it) }
        }
        throw AiException(message, retryable = allRetryable, retryAfterMs = (error as? AiException)?.retryAfterMs)
    }

    /**
     * A failed lookup is the one transport error that looks like a fault in the app when it is
     * not: the keys were never sent, nothing left the phone, and the usual cause is the phone's
     * own connection — no data, a VPN that is not passing traffic, a Private DNS hostname that
     * does not answer, or a network that filters the address. Shown once, under the per-provider
     * lines, rather than repeating the same advice for each host.
     */
    private fun networkNote(errors: List<Exception>): String? {
        if (errors.isEmpty() || errors.any { it !is UnknownHostException }) return null
        return "The phone could not look up the address, so this request never left the app. " +
            "That is the connection, not the API key: check mobile data or Wi-Fi, Private DNS, " +
            "and any VPN, or open the address in the phone's browser, then ask again."
    }

    /**
     * Reads published tasting notes for one bottle, with the pages they came from.
     *
     * Gemini's own Search grounding is not used: Google gates it behind billing, so a free key
     * answers 429 with a limit of zero and waiting never helps. The app retrieves instead — a
     * keyless search for the producer, cuvée and vintage, then the pages that answer it — and the
     * model is asked only to report what those pages say. Either provider can do that, it costs
     * nothing, and the note keeps the URLs it came from.
     */
    suspend fun sourcedNotes(wine: Wine): SourcedNotes = withContext(Dispatchers.IO) {
        val query = notesQuery(wine)
        val hits = web.search(query)
        if (hits.isEmpty()) throw AiException("The web had nothing for \"$query\". Nothing was saved.")
        val excerpts = hits
            .filter { it.url.startsWith("http") }
            .take(6)
            .mapNotNull { hit ->
                val text = runCatching { web.pageText(hit.url, 4000) }.getOrNull().orEmpty()
                if (text.length < 400) null else hit to text
            }
            .take(2)
        val known = (excerpts.map { it.first.url } + hits.take(8).map { it.url }).distinct()
        val evidence = buildString {
            append("SEARCH RESULTS for \"").append(query).append("\"\n")
            hits.take(8).forEach { hit ->
                append("- ").append(hit.title.ifBlank { hit.url }).append(" (").append(hit.url)
                    .append(")\n")
                if (hit.snippet.isNotBlank()) append("  ").append(hit.snippet).append("\n")
            }
            excerpts.forEach { (hit, text) ->
                append("\nPAGE ").append(hit.url).append("\n").append(text).append("\n")
            }
        }
        val answer = complete(
            system = extractPrompt(wine),
            turns = listOf(ChatMessage(role = "user", text = evidence)),
            jsonMode = false,
            thinking = Thinking.OFF,
            temperature = 0.2,
            maxTokens = 1600
        )
        parseExtraction(answer, known)
    }

    /**
     * The bottle's own photograph, from the page that named it.
     *
     * No model is asked: the page declares the picture in its head, and the app fetches it. Null is
     * a real answer — the picture is a check on the record, not a requirement for having one.
     */
    suspend fun bottleImage(pageUrl: String): ByteArray? = web.pageImage(pageUrl)

    /**
     * Where to go tonight: the app looks, and the model may only choose between what it found.
     *
     * This is the answer to the failure that made the sommelier feel like a search box with a
     * cellar attached: asked for dinner in Portland, it named three restaurants that had been closed
     * for years. Nothing was wrong with the model's wine knowledge — it was answering from memory,
     * and no code was checking the answer against anything. So the app fetches the pages first, drops
     * every place they mark closed ([PlaceScout.closedReason]), and asks the model only which number
     * to pick and why. The reply is then written by [PlaceScout.answer] from the fetched record, so a
     * restaurant the model remembers cannot reach the screen.
     *
     * A source that cannot be reached throws: "the phone is offline" and "the town has no wine
     * bar" are different answers, and only the second one is worth wording as an empty result.
     */
    suspend fun scout(
        ask: PlaceScout.Ask,
        brief: String,
        region: Region? = null
    ): PlaceScout.Report = PlaceScout.research(ask, brief, sources(region))

    /**
     * The place answer as the chat shows it: research, or the honest "could not check" reply.
     *
     * The catch lives in [PlaceScout.researchOrSay] so the live run proves the same text the chat
     * produces, rather than a second copy of it in a test.
     */
    suspend fun scoutReply(
        ask: PlaceScout.Ask,
        brief: String,
        region: Region? = null
    ): String = PlaceScout.researchOrSay(ask, brief, sources(region))

    /**
     * The three sources the chat uses: the map, the page reader, and the provider.
     *
     * Two short caches sit here rather than in [PlaceScout] because they are a property of this
     * phone's network, not of the reasoning: a second question about the same town this evening
     * should not re-ask the map or re-read four pages. Twenty minutes is short enough that a
     * closure published this afternoon is still picked up, and long enough that a table's follow-up
     * question ("what about something cheaper?") costs nothing.
     */
    private fun sources(region: Region?): PlaceScout.Sources = object : PlaceScout.Sources {

        override suspend fun region(): Region? = region

        override suspend fun towns(ask: PlaceScout.Ask): List<PlaceScout.Town> =
            OsmPlaces.towns(ask.where)

        override suspend fun discover(ask: PlaceScout.Ask, box: String): List<PlaceScout.Venue> {
            val key = PlaceScout.cacheKey(ask)
            placeCache.get(key)?.let { return it }

            // Google first, when the phone has a Gemini key. Its search grounding knows the
            // restaurants with serious lists that the map cannot see (the map only finds what a
            // volunteer tagged "wine"), and the pages it cites are what the research then reads.
            // The map is the second opinion, not the other way round: it is the source that needed
            // a mirror, a rate-limit apology and a category guess to work at all.
            val cited = groundedVenues(ask)
            if (cited.isNotEmpty()) {
                placeCache.put(key, cited)
                return cited
            }

            var refusal: AiException? = null
            val mapped = try {
                mapVenues(ask, box)
            } catch (e: AiException) {
                refusal = e
                emptyList()
            } catch (e: Exception) {
                refusal = AiException(OsmPlaces.BUSY, retryable = true)
                emptyList()
            }
            val found = if (mapped.isNotEmpty()) mapped else try {
                searchVenues(ask)
            } catch (e: AiException) {
                throw refusal ?: e
            }
            if (found.isEmpty() && refusal != null) throw refusal
            if (found.isNotEmpty()) placeCache.put(key, found)
            return found
        }

        /**
         * The towns a short drive away, asked of Google when the settled town came back empty.
         *
         * The names are the model's, and they are used only as *where to look next*: the research
         * runs its whole pipeline for each one, so a town the model invented simply yields nothing
         * and the next is tried. Two towns, because three is a road trip.
         */
        override suspend fun nearby(ask: PlaceScout.Ask): List<PlaceScout.Ask> {
            val key = "near|" + PlaceScout.cacheKey(ask)
            nearbyCache.get(key)?.let { return it }
            val towns = runCatching { grounding()?.nearby(ask).orEmpty() }
                .getOrDefault(emptyList())
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .filter { PlaceScout.fold(it) != PlaceScout.fold(ask.where) }
                .distinctBy { PlaceScout.fold(it) }
                .take(PlaceScout.MAX_NEARBY)
            val asks = towns.map { ask.copy(where = it, basis = "") }
            if (asks.isNotEmpty()) nearbyCache.put(key, asks)
            return asks
        }

        override suspend fun page(url: String): String {
            pageCache.get(url)?.let { return it }
            val text = withTimeoutOrNull(8_000) {
                runCatching { web.pageText(url, 2_000) }.getOrNull()
            }.orEmpty()
            if (text.isNotBlank()) pageCache.put(url, text)
            return text
        }

        override suspend fun pick(system: String, evidence: String): String = complete(
            system = system,
            turns = listOf(ChatMessage(role = "user", text = evidence)),
            job = AiJob.CHAT,
            jsonMode = true,
            // Choosing between a few numbered places is not a reasoning problem; the reasoning in
            // this feature was the app's, and it already happened.
            thinking = Thinking.LOW,
            temperature = 0.2,
            maxTokens = 900
        )
    }

    /**
     * Grounded discovery, or null when this phone has no Gemini key.
     *
     * Built per call because the key is a setting the user can change at any time; the transport
     * underneath is shared, so no second connection pool is created.
     */
    private fun grounding(): GeminiGrounding? =
        settings.key(AiProvider.GEMINI).trim().takeIf { it.isNotBlank() }
            ?.let { GeminiGrounding(it, transport = groundingTransport) }

    /**
     * What Google's grounding named, with a page wherever it cited one.
     *
     * A cited page is evidence as it stands, and the research reads it. A bare name is not: the
     * prompt deliberately stops asking for websites, because a town's one wine bar often has none,
     * so each name gets its own search and [PlaceScout.confirmed] — the same confirmation the
     * keyless path uses — before it is allowed onto the list. Names are capped like the map's, and
     * the pause between them is the same politeness to the search engine.
     */
    private suspend fun groundedVenues(ask: PlaceScout.Ask): List<PlaceScout.Venue> =
        withContext(Dispatchers.IO) {
            val proposals = runCatching { grounding()?.find(ask).orEmpty() }
                .getOrDefault(emptyList())
            if (proposals.isEmpty()) return@withContext emptyList()
            val venues = proposals
                .filter { it.url.isNotBlank() }
                .map { PlaceScout.Venue(name = it.name, url = it.url, title = it.name) }
                .toMutableList()
            for (proposal in proposals.filter { it.url.isBlank() }.take(PlaceScout.MAX_CANDIDATES)) {
                val hits = runCatching {
                    web.search(PlaceScout.confirmQuery(proposal.name, ask))
                }.getOrNull().orEmpty()
                PlaceScout.confirmed(proposal.name, hits, ask)?.let { venues += it }
                delay(150)
            }
            venues
        }

    /** The map's places, as venues whose address can prove the town. */
    private suspend fun mapVenues(ask: PlaceScout.Ask, box: String): List<PlaceScout.Venue> =
        OsmPlaces.find(ask, box).map(OsmPlaces::asVenue)

    /**
     * The search-engine path, kept for towns the map cannot answer for.
     *
     * Discovery only produces names, and each one is searched for on its own: that is where a
     * closure shows up, and a name no page confirms is not a place. Pages are read by the research
     * itself, so nothing is fetched twice here.
     */
    private suspend fun searchVenues(ask: PlaceScout.Ask): List<PlaceScout.Venue> =
        withContext(Dispatchers.IO) {
            val hits = mutableListOf<WebLookup.Hit>()
            var failure: Exception? = null
            for (query in PlaceScout.discoveryQueries(ask)) {
                try {
                    hits += web.search(query)
                } catch (e: Exception) {
                    if (failure == null) failure = e
                }
            }
            if (hits.isEmpty()) {
                failure?.let { throw it }
                return@withContext emptyList()
            }
            val confirmed = mutableListOf<PlaceScout.Venue>()
            var refused: Exception? = null
            for (name in PlaceScout.candidates(hits, ask).take(PlaceScout.MAX_CANDIDATES)) {
                val own = try {
                    web.search(PlaceScout.confirmQuery(name, ask))
                } catch (e: Exception) {
                    if (refused == null) refused = e
                    emptyList()
                }
                PlaceScout.confirmed(name, own, ask)?.let { confirmed += it }
                // Politeness to a keyless endpoint that throttles: a burst is how a working lookup
                // starts returning nothing for the rest of the minute.
                delay(200)
            }
            if (confirmed.isEmpty() && refused != null) throw refused
            PlaceScout.ranked(confirmed)
        }

    /**
     * A picture for a bottle the app already holds, from a page that names it.
     *
     * The same keyless search the notes lookup makes, without the model: the first three pages that
     * answer are asked whether they declare a picture, and a page has to carry the wine's own
     * producer and name before its picture is trusted. Null when none of them does — a page that
     * publishes no photograph is an answer, not a failure.
     */
    suspend fun bottleImageFor(wine: Wine): ByteArray? = withContext(Dispatchers.IO) {
        val base = listOf(wine.producer, wine.name).filter { it.isNotBlank() }.joinToString(" ")
        if (base.isBlank()) return@withContext null
        // The exact bottling first, then the label itself, then the wine without its vintage: an
        // older bottle often has no page for its year, and the label is what has to be recognised.
        val queries = listOf(
            listOfNotNull(base, wine.vintage?.toString()).joinToString(" "),
            "$base wine label",
            base
        ).distinct()
        val pages = mutableListOf<String>()
        for (query in queries) {
            val hits = runCatching { web.search(query) }.getOrNull().orEmpty()
            for (hit in hits) {
                if (pages.size >= 5) break
                if (readable(hit.url) && hit.url !in pages) pages += hit.url
            }
            if (pages.size >= 5) break
        }
        for (page in pages) {
            val picture = runCatching { web.pageImage(page, wine.name, wine.producer) }.getOrNull()
            if (picture != null && picture.isNotEmpty()) return@withContext picture
        }
        null
    }

    /** A page worth the request: a page, not a PDF or a site that refuses a phone. */
    private fun readable(url: String): Boolean {
        val low = url.lowercase()
        if (!low.startsWith("http")) return false
        if (low.endsWith(".pdf") || ".pdf?" in low) return false
        return PICTURE_DEAD_ENDS.none { low.contains(it) }
    }

    /** Pages that answer a phone with a wall, a script, or a product page that never renders. */
    private val PICTURE_DEAD_ENDS = listOf(
        "cellartracker.com",
        "wine-searcher.com",
        "vivino.com",
        "jamessuckling.com",
        "wine.com/product",
        "youtube.com",
        "facebook.com",
        "instagram.com"
    )

    /**
     * The bottlings a few typed words name, before any page has been read.
     *
     * The user's own words are the query — "2025 stoller rose" — and one keyless search plus one
     * extraction over its titles and snippets answer with the wines those results actually name.
     * Nothing is opened here: a page read is the expensive half and is spent only for the bottle
     * the user picks, through the ordinary details lookup. An empty list is a real answer.
     */
    suspend fun findBottlings(query: String): List<BottleCandidate> = withContext(Dispatchers.IO) {
        val clean = query.trim().replace(Regex("\\s+"), " ").take(120)
        if (clean.count { it.isLetterOrDigit() } < 3) {
            throw AiException("Type at least three characters of the wine's name.")
        }
        val hits = web.search(clean)
        if (hits.isEmpty()) {
            throw AiException(
                "The web search had nothing for \"$clean\". Type the bottle in, or scan its label."
            )
        }
        val answer = complete(
            system = BottleSearch.prompt(clean),
            turns = listOf(ChatMessage(role = "user", text = BottleSearch.evidence(hits))),
            jsonMode = true,
            thinking = Thinking.OFF,
            temperature = 0.2,
            maxTokens = 1000
        )
        BottleSearch.parse(answer, hits.map { it.url })
    }

    /**
     * The ABV alone, for a bottle whose pages were read but never printed one.
     *
     * The notes lookup reads whatever page answers "tasting notes", and a producer's page often
     * gives prose without the figure; a shop's listing almost always shows "% vol". This asks that
     * question directly and reads the pages that answer it, so the alcohol gate has a number
     * instead of UNKNOWN. Nothing is estimated: a page that prints no figure means null.
     */
    suspend fun abvOf(wine: Wine): Double? = withContext(Dispatchers.IO) {
        val query = buildList {
            if (wine.producer.isNotBlank()) add(wine.producer)
            if (wine.name.isNotBlank()) add(wine.name)
            wine.vintage?.let { add(it.toString()) }
            add("alcohol by volume percent")
        }.joinToString(" ")
        // A search that could not be reached throws: "nobody answered" and "no page printed a
        // figure" are different answers, and only the second one is worth stamping.
        val hits = web.search(query)
        if (hits.isEmpty()) return@withContext null
        val excerpts = hits
            .take(6)
            .mapNotNull { hit ->
                val text = runCatching { web.pageText(hit.url, 2500) }.getOrNull().orEmpty()
                if (text.length < 200) null else hit to text
            }
            .take(2)
        val evidence = buildString {
            hits.take(6).forEach { hit ->
                append("- ").append(hit.title.ifBlank { hit.url }).append(" (").append(hit.url)
                    .append(")\n  ").append(hit.snippet).append("\n")
            }
            excerpts.forEach { (hit, text) ->
                append("\nPAGE ").append(hit.url).append("\n").append(text).append("\n")
            }
        }
        val answer = complete(
            system = abvPrompt(wine),
            turns = listOf(ChatMessage(role = "user", text = evidence)),
            jsonMode = false,
            thinking = Thinking.OFF,
            temperature = 0.0,
            maxTokens = 40
        )
        Regex("(?im)^\\s*ABV\\s*:\\s*([0-9]{1,2}(?:[.,][0-9]{1,2})?)")
            .find(answer)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
            ?.takeIf { it in 4.0..22.0 }
    }

    private fun abvPrompt(wine: Wine): String {
        val identity = buildList {
            add("producer: ${wine.producer.ifBlank { "unknown" }}")
            if (wine.name.isNotBlank()) add("cuvee: ${wine.name}")
            wine.vintage?.let { add("vintage: $it") }
            if (wine.appellation.isNotBlank()) add("appellation: ${wine.appellation}")
        }.joinToString("\n")
        return """
        Pages were fetched for one bottle. Print the alcohol by volume they state for it, and
        nothing else.

        $identity

        Reply with exactly one line:

        ABV: 14.2

        - Use only a figure the text prints for this bottling ("14.1% vol", "13,5 %").
        - If no page states it, reply "ABV:" with nothing after it.
        - Never estimate it from the grape, the region, or what similar wines usually carry.
        """.trimIndent()
    }

    private fun notesQuery(wine: Wine): String = buildList {
        if (wine.producer.isNotBlank()) add(wine.producer)
        if (wine.name.isNotBlank()) add(wine.name)
        wine.vintage?.let { add(it.toString()) }
        add("tasting notes")
    }.joinToString(" ")

    private fun extractPrompt(wine: Wine): String {
        val identity = buildList {
            add("producer: ${wine.producer.ifBlank { "unknown" }}")
            if (wine.name.isNotBlank()) add("cuvee: ${wine.name}")
            wine.vintage?.let { add("vintage: $it") }
            if (wine.region.isNotBlank()) add("region: ${wine.region}")
            if (wine.appellation.isNotBlank()) add("appellation: ${wine.appellation}")
            if (wine.country.isNotBlank()) add("country: ${wine.country}")
            if (wine.grapes.isNotEmpty()) add("grapes: ${wine.grapes.joinToString(", ")}")
            add("type: ${wine.type.label}")
        }.joinToString("\n")
        return """
        You are reading search results and page text that were fetched for one bottle. Report only
        what those pages say about this wine. You have no other knowledge to fall back on: if the
        text is thin, the note is thin, and if it describes a different wine, it is not a match.

        $identity

        Reply in exactly this shape and nothing else. No preamble, no markdown, no JSON:

        MATCH: yes or no
        NOTE: two to four sentences
        ABV: number or empty
        DRINK: from-to or empty
        COUNTRY: name or empty
        REGION: name or empty
        APPELLATION: name or empty
        GRAPES: grape, grape, or empty
        TAGS: tag, tag, tag
        SOURCES: url url
        CAVEAT: one sentence

        - MATCH is yes only when the text describes this producer and this cuvée. Another vintage
          of the same wine is acceptable when CAVEAT says which one.
        - NOTE is 2 to 4 sentences taken from the text: how it smells, tastes and feels, and how it
          drinks now. Paraphrase; never copy a sentence word for word.
        - ABV is the alcohol figure the text prints for this bottling (14.1 from "14.1% vol"). Leave
          it empty when no page states it: never estimate it from the grape or the region.
        - DRINK is the drinking window the text states for this bottling: 2027-2034, or one end
          only (2029- when it says "best from 2029", -2031 when it says "drink by 2031"). Years
          only, in that order. Leave it empty when no page prints a window: never work one out from
          the vintage, the grape, the region or what similar wines usually keep.
        - COUNTRY, REGION and APPELLATION are what the text names for this bottling, as it writes
          them. Leave empty anything it does not name: do not repeat the identity above.
        - GRAPES is 1 to 5 grapes the text lists for it, separated by commas, empty when it lists
          none.
        - TAGS is 3 to 6 short tags the text supports (blackcurrant, cedar), separated by commas.
        - SOURCES is the URLs you used, copied exactly from the list above, separated by spaces.
        - CAVEAT is one sentence on what is uncertain — a neighbouring vintage, another bottling, a
          thin source — or empty.
        - If the text does not describe this bottling, write MATCH: no, leave NOTE empty, and say
          what was missing in CAVEAT. Never fall back on what the grape usually tastes like.
        - Answer with the record even when the pages are thin. Do not explain yourself.
        """.trimIndent()
    }

    /**
     * Reads the answer in whichever shape it arrives.
     *
     * The call above asks for five plain lines, not JSON: a compatibility layer that quietly drops
     * `response_format` turns a JSON request into prose, and the note is then lost for no reason.
     * JSON is still accepted first, because other calls in the app use it and models volunteer it.
     */
    private fun parseExtraction(raw: String, known: List<String>): SourcedNotes {
        jsonNotes(raw, known)?.let { return it }

        val matchLine = Regex("(?im)^\\s*MATCH\\s*:\\s*(yes|no|true|false)")
            .find(raw)?.groupValues?.get(1)?.lowercase()
        val note = Regex("(?is)\\bNOTE\\s*:\\s*(.+?)(?=\\n\\s*(ABV|TAGS|SOURCES|CAVEAT)\\s*:|\\z)")
            .find(raw)?.groupValues?.get(1)?.trim().orEmpty()
        val abv = Regex("(?im)^\\s*ABV\\s*:\\s*([0-9]{1,2}(?:[.,][0-9]{1,2})?)\\s*%?")
            .find(raw)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
            ?.takeIf { it in 4.0..22.0 }
        val tags = Regex("(?im)^\\s*TAGS\\s*:\\s*(.+)$").find(raw)?.groupValues?.get(1)
            ?.split(',')?.map { it.trim().trim('"', '\'', '.') }
            ?.filter { it.isNotBlank() && it.length <= 24 }
            ?.take(6)
            .orEmpty()
        val caveat = Regex("(?im)^\\s*CAVEAT\\s*:\\s*(.*)$").find(raw)?.groupValues?.get(1)
            ?.trim().orEmpty()
        val cited = Regex("https?://[^\\s,;)\\]]+")
            .findAll(Regex("(?im)^\\s*SOURCES\\s*:\\s*(.*)$").find(raw)?.groupValues?.get(1).orEmpty())
            .map { it.value.trimEnd('.', ',') }
            .filter { known.contains(it) }
            .distinct()
            .toList()
        // The window, the place and the grapes come from the same answer as the note: one request,
        // every fact. A page that printed none of them leaves them empty rather than guessed.
        val (windowFrom, windowTo) = DetailsFacts.window(field(raw, "DRINK"))
        val country = DetailsFacts.place(field(raw, "COUNTRY"), 40)
        val region = DetailsFacts.place(field(raw, "REGION"), 60)
        val appellation = DetailsFacts.place(field(raw, "APPELLATION"), 60)
        val grapes = DetailsFacts.grapes(field(raw, "GRAPES"))
        // A page that says it is another wine can still be useful for nothing at all: its years and
        // its appellation describe that wine, not this one.
        val matched = matchLine != "no"

        if (matchLine == null && note.isBlank()) {
            // Nothing recognisable came back. Say what did, rather than a message that leaves the
            // reader guessing; the batch stops after three of these and saves nothing.
            val excerpt = raw.replace(Regex("\\s+"), " ").trim().take(200)
            throw AiException("The model's answer could not be read: \"$excerpt\"")
        }

        val refusal = Regex(
            "(?i)(does not|doesn't|cannot|can't|could not|no information|not enough).{0,40}" +
                "(describe|match|information|say|find|identify)"
        ).containsMatchIn(note)
        // A model that has nothing will still describe the wine's colour and region and then admit
        // the tasting notes are missing. That admission is a miss, not a tasting note: saving it
        // puts a database summary in the record under a critic's authority.
        val metaAnswer = Regex(
            "(?i)(no (specific )?tasting notes|" +
                "tasting notes (for|of) .{0,60}(are|is|were) (not|un)|" +
                "not (available|published|provided|found)|while (specific )?tasting notes)"
        ).containsMatchIn(note)
        val usable = note.length >= 40 && !refusal && !metaAnswer
        if (!usable) {
            // The note is unusable, but the figure may not be: an unhelpful description and a
            // printed ABV are different claims from the same page, and the gate needs the second.
            return SourcedNotes(
                notes = "",
                tags = emptyList(),
                sources = emptyList(),
                caveat = caveat.ifBlank {
                    if (matchLine == "no") "No page found described this bottling." else ""
                },
                alcohol = abv,
                drinkFrom = if (matched) windowFrom else null,
                drinkTo = if (matched) windowTo else null,
                country = if (matched) country else "",
                region = if (matched) region else "",
                appellation = if (matched) appellation else "",
                grapes = if (matched) grapes else emptyList()
            )
        }
        return SourcedNotes(
            notes = note,
            tags = tags,
            sources = cited.ifEmpty { known.take(2) },
            caveat = caveat,
            alcohol = abv,
            drinkFrom = if (matched) windowFrom else null,
            drinkTo = if (matched) windowTo else null,
            country = if (matched) country else "",
            region = if (matched) region else "",
            appellation = if (matched) appellation else "",
            grapes = if (matched) grapes else emptyList()
        )
    }

    /** One labelled line of the answer, or empty when the model wrote none. */
    private fun field(raw: String, label: String): String =
        Regex("(?im)^\\s*" + label + "\\s*:\\s*(.*)$")
            .find(raw)?.groupValues?.get(1).orEmpty()

    private fun jsonNotes(raw: String, known: List<String>): SourcedNotes? {
        val text = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val inner = text.substringAfter('{', "")
        if (inner.isEmpty() || !inner.contains('}')) return null
        val root = runCatching {
            Json.parseToJsonElement("{" + inner.substringBeforeLast('}') + "}").jsonObject
        }.getOrNull() ?: return null
        val matched = root["match"]?.jsonPrimitive?.contentOrNull?.equals("true", true) == true
        val notes = if (matched) root["notes"]?.jsonPrimitive?.contentOrNull.orEmpty().trim() else ""
        val tags = root["flavor_tags"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
            ?.filter { it.isNotBlank() }
            ?.take(6)
            .orEmpty()
        val caveat = root["caveat"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
        val alcohol = root["alcohol"]?.jsonPrimitive?.contentOrNull
            ?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it in 4.0..22.0 }
        val drink = root["drink"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val (windowFrom, windowTo) = DetailsFacts.sane(
            root["drink_from"]?.jsonPrimitive?.contentOrNull?.trim()?.toIntOrNull()
                ?: DetailsFacts.window(drink).first,
            root["drink_to"]?.jsonPrimitive?.contentOrNull?.trim()?.toIntOrNull()
                ?: DetailsFacts.window(drink).second
        )
        val country = DetailsFacts.place(
            root["country"]?.jsonPrimitive?.contentOrNull.orEmpty(), 40
        )
        val region = DetailsFacts.place(root["region"]?.jsonPrimitive?.contentOrNull.orEmpty(), 60)
        val appellation = DetailsFacts.place(
            root["appellation"]?.jsonPrimitive?.contentOrNull.orEmpty(), 60
        )
        val grapes = root["grapes"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
            ?.filter { it.isNotBlank() && it.length <= 30 }
            ?.distinct()
            ?.take(6)
            .orEmpty()
        // JSON may carry an explicit "match": false; anything else counts as describing the wine.
        val describesThisWine =
            root["match"]?.jsonPrimitive?.contentOrNull?.equals("false", true) != true
        val cited = root["sources"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
            ?.filter { it.isNotBlank() && known.contains(it) }
            ?.distinct()
            .orEmpty()
        return SourcedNotes(
            notes = notes,
            tags = tags,
            sources = if (notes.isBlank()) emptyList() else cited.ifEmpty { known.take(2) },
            caveat = caveat,
            alcohol = alcohol,
            drinkFrom = if (describesThisWine) windowFrom else null,
            drinkTo = if (describesThisWine) windowTo else null,
            country = if (describesThisWine) country else "",
            region = if (describesThisWine) region else "",
            appellation = if (describesThisWine) appellation else "",
            grapes = if (describesThisWine) grapes else emptyList()
        )
    }

    suspend fun listModels(provider: AiProvider): List<String> = withContext(Dispatchers.IO) {
        val key = settings.key(provider)
        if (key.isBlank()) throw AiException("Add a ${provider.label} API key first.")
        val request = Request.Builder()
            .url("${settings.baseUrl(provider)}/models")
            .addHeader("Authorization", "Bearer $key")
            .get()
            .build()
        http.newCall(request).execute().use { res ->
            val body = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw AiException(describeError(provider, res.code, body))
            val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
                ?: throw AiException("${provider.label} returned an unexpected response.")
            root["data"]?.jsonArray
                ?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
                ?.sorted()
                ?: emptyList()
        }
    }

    private fun resolveTargets(
        provider: AiProvider?,
        model: String?,
        vision: Boolean,
        job: AiJob
    ): List<Target> {
        val providers = provider?.let { listOf(it) }
            ?: AiProvider.order(job, settings.configuredProviders)
        return providers.mapNotNull { candidate ->
            val key = settings.key(candidate)
            if (key.isBlank()) return@mapNotNull null
            // An attached photo still needs a model that can see it, whichever job asked.
            val chosen = model?.takeIf { provider != null && it.isNotBlank() }
                ?: when {
                    vision -> settings.visionModel(candidate)
                    job == AiJob.READ -> settings.textModel(candidate)
                    else -> settings.adviceModel(candidate)
                }
            Target(
                provider = candidate,
                baseUrl = settings.baseUrl(candidate),
                key = key,
                model = chosen
            )
        }
    }

    private suspend fun attempt(
        target: Target,
        system: String,
        turns: List<ChatMessage>,
        images: List<String>,
        jsonMode: Boolean,
        thinking: Thinking,
        temperature: Double,
        maxTokens: Int,
        onBudgetWait: ((Long) -> Unit)?
    ): String {
        val limitKey = "${target.provider.id}:${target.model}"
        val estimate = RateLimiter.estimateTokens(
            promptChars = system.length + turns.sumOf { it.text.length },
            images = images.size,
            maxTokens = maxTokens
        )
        limiter.awaitBudget(
            key = limitKey,
            estimatedTokens = estimate,
            minIntervalMs = 60_000L / settings.requestsPerMinute(target.provider).coerceAtLeast(1),
            onWait = onBudgetWait
        )

        var response = post(
            target,
            chatPayload(target, system, turns, images, jsonMode, thinking, temperature, maxTokens)
        )
        if (!response.isSuccessful && response.code == 400) {
            // Some models reject response_format or reasoning_effort, and take at most a few
            // images. Retry with the plainest request that still carries a photo.
            response.close()
            response = post(
                target,
                chatPayload(target, system, turns, images.take(1), false, null, temperature, maxTokens)
            )
        }
        if (!response.isSuccessful && response.code == 413) {
            // Larger than this tier's minute. A smaller field set does not help — the conversation
            // is the size — so the one retry keeps the question and drops the history.
            response.close()
            response = post(
                target,
                leanPayload(target, system, turns, images, temperature, maxTokens)
            )
        }
        return response.use { res ->
            val body = res.body?.string().orEmpty()
            val headers = res.headers.toMap()
            if (!res.isSuccessful) {
                if (res.code == 429 || res.code == 413) {
                    val retryAfter = retryAfterMs(res, body)
                    limiter.penalise(limitKey, retryAfter)
                    throw AiException(
                        describeError(target.provider, res.code, body),
                        retryable = true,
                        retryAfterMs = retryAfter
                    )
                }
                throw AiException(
                    describeError(target.provider, res.code, body),
                    retryable = res.code >= 500,
                    fatal = res.code == 401 || res.code == 402 || res.code == 403
                )
            }
            limiter.record(limitKey, headers)
            // Billed whether or not the answer parses, so the usage is recorded before the
            // content is read; a reply cut off by the token budget still costs its tokens.
            recordUsage(target, body)
            parseContent(target.provider, body)
        }
    }

    /**
     * Sent asynchronously rather than with `execute()`, so a cancelled coroutine — the Stop button,
     * or a scan deadline — actually cancels the HTTP call. A blocking execute keeps a thread parked
     * on the socket for as long as the provider feels like taking, long after the screen gave up.
     */
    private suspend fun post(target: Target, payload: String): Response {
        val request = Request.Builder()
            .url("${target.baseUrl}/chat/completions")
            .addHeader("Authorization", "Bearer ${target.key}")
            .addHeader("Content-Type", "application/json")
            .post(payload.toRequestBody(jsonMedia))
            .build()
        return suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isActive) continuation.resume(response) else response.close()
                }

                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            })
        }
    }

    // The request body lives in AiPayload.kt, with the per-provider thinking map and the output
    // budget: all three are pure, so a test can pin the exact JSON that used to return an empty
    // answer (reasoning_effort "none" is a 400 on Groq, and the 400-retry then let reasoning eat
    // the whole token budget).

    private fun retryAfterMs(res: Response, body: String): Long? {
        RateLimiter.parseDurationMillis(res.header("retry-after"))?.let { return it }
        Regex("(?i)try again in ([0-9]+(?:\\.[0-9]+)?)s")
            .find(body)
            ?.groupValues
            ?.getOrNull(1)
            ?.toDoubleOrNull()
            ?.let { return (it * 1000).toLong() }
        return RateLimiter.parseDurationMillis(res.header("x-ratelimit-reset-tokens"))
    }

    /**
     * Adds what this answer cost to the month's total, from the provider's own counts.
     *
     * Every OpenAI-compatible provider returns `usage` on a successful answer. A provider that
     * does not is left uncounted rather than estimated: the cap is the one number that must not be
     * fiction.
     */
    private fun recordUsage(target: Target, body: String) {
        val usage = runCatching {
            Json.parseToJsonElement(body).jsonObject["usage"]?.jsonObject
        }.getOrNull() ?: return
        val input = usage["prompt_tokens"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return
        val output = usage["completion_tokens"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        settings.spend.add(target.provider, target.model, input, output)
    }

    private fun parseContent(provider: AiProvider, body: String): String {
        val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: throw AiException("${provider.label} returned something that is not JSON.")
        val firstChoice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
        // A cut-off answer (finish_reason "length") with an empty body is not something to
        // parse: reasoning text is not the answer, and feeding it to the wine parsers is how a
        // truncated reply becomes a confident-looking wrong card.
        val finished = firstChoice?.get("finish_reason")?.jsonPrimitive?.contentOrNull
        if (finished == "length") {
            throw AiException("The model ran out of room before finishing. Try again.", retryable = true)
        }
        val message = firstChoice?.get("message")?.jsonObject
        val content = message?.get("content")?.jsonPrimitive?.contentOrNull
        val reasoning = message?.get("reasoning")?.jsonPrimitive?.contentOrNull
        val text = content?.takeIf { it.isNotBlank() } ?: reasoning?.takeIf { it.isNotBlank() }
        return text ?: throw AiException("The model returned an empty answer. Try again.")
    }

    private fun describeError(provider: AiProvider, code: Int, body: String): String {
        val detail = runCatching {
            Json.parseToJsonElement(body).jsonObject["error"]?.jsonObject
                ?.get("message")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        val base = when (code) {
            401, 403 -> "${provider.label} rejected the API key ($code). Check it in Settings."
            402 -> "${provider.label} says the account is out of credit (402)."
            413 -> "That photo is too big for ${provider.label} (413). Try again from a little further back."
            429 -> "${provider.label} rate limit hit (429). Another provider answers instead if one is set; the batch queue retries on its own."
            in 500..599 -> "${provider.label} had a server error ($code). Try again shortly."
            400 -> "${provider.label} rejected the request (400)."
            else -> "${provider.label} request failed ($code)."
        }
        return if (detail.isNullOrBlank()) base else "$base\n$detail"
    }
}