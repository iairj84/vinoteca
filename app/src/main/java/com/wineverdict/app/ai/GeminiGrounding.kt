package com.wineverdict.app.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Where to go tonight, asked of Google itself rather than of a keyless map.
 *
 * The map path works but it is a guess about what the map happens to tag: Nominatim finds places
 * whose name or category says "wine", so a restaurant with a serious list under a chef's name is
 * invisible to it, and every map is stale about closures in a different way. The phone already has
 * a Gemini key, and Gemini's own [Google Search grounding] answers exactly this question: it
 * searches, reads, and hands back the pages it used.
 *
 * What comes back is used only as *discovery* — names, and the pages Google cited for them. The
 * research tail is untouched: each page is fetched, a closure on it drops the place, a page that
 * never says wine drops it, and a page that does not place it in the asked town drops it. So the
 * model cannot put a restaurant on the screen; it can only propose a place that then has to survive
 * being read.
 *
 * Two things this deliberately does not do:
 *
 *  - it does not trust the model's prose as evidence. A name is read out of the answer only when the
 *    line is the shape the prompt asked for (`Name | Town`), and a name with no cited page is then
 *    searched for on its own and has to be confirmed by [PlaceScout.confirmed] before the research
 *    sees it. A model that answers in sentences is answering from memory, which is no answer.
 *  - it does not use the OpenAI-compatible endpoint the rest of the app talks to, because Google's
 *    search tools only exist on the native `generateContent` API. That is why this file speaks its
 *    own request shape.
 *
 * [Google Search grounding]: https://ai.google.dev/gemini-api/docs/google-search
 */
class GeminiGrounding(
    private val key: String,
    private val model: String = MODEL,
    private val transport: Transport = HttpTransport()
) {

    /** The two calls this needs, injected so a test can answer without a key or a network. */
    interface Transport {
        /** The response body for a grounded request, or null when the call failed. */
        suspend fun post(url: String, key: String, body: String): String?

        /**
         * The page a citation actually points at.
         *
         * Grounding citations are wrapped in Google's own redirect, so the host the app prints and
         * the page it fetches both have to come from the resolved address, not the redirect.
         */
        suspend fun resolve(url: String): String?
    }

    /** One place Google named: a cited page when it cited one, a bare name when it did not. */
    data class Proposal(
        val name: String,
        val town: String = "",
        /** The venue's own page. Blank for a name that still has to earn one. */
        val url: String = ""
    )

    /**
     * Venues Google's search named for this question, best first.
     *
     * Empty is a real answer and so is a failed call: the caller falls back to the map, which is the
     * second opinion rather than the only one. Nothing here throws for a network reason.
     */
    suspend fun find(ask: PlaceScout.Ask, where: String = ask.where): List<Proposal> {
        if (key.isBlank()) return emptyList()
        val place = where.trim()
        if (place.isBlank()) return emptyList()
        val response = call(venuesRequest(ask, place)) ?: return emptyList()
        return merge(cited(response), list(response), place)
    }

    /**
     * Towns near [ask] worth trying when it has none of its own, nearest first.
     *
     * The ground truth here is that a town of forty thousand people can have one wine bar, and that
     * bar can be the wrong side of a boundary the map draws and a person does not. "Nowhere" sends
     * the reader to a search engine; one town over is a different, honest answer, and its places are
     * still read before they are named. The reply says which town they are in.
     */
    suspend fun nearby(ask: PlaceScout.Ask): List<String> {
        if (key.isBlank()) return emptyList()
        val place = ask.where.trim()
        if (place.isBlank()) return emptyList()
        val response = call(nearbyRequest(ask, place)) ?: return emptyList()
        // This prompt asks for `Town | Venue`, so the parsed name is the town: the town is what the
        // research needs, and the venue is found again by the ordinary discovery for that town.
        return list(response).map { it.name }.take(MAX_TOWNS)
    }

    /** The cited pages as venues, kept ahead of the bare names the model wrote. */
    private suspend fun cited(response: String): List<Proposal> {
        val venues = mutableListOf<Proposal>()
        val hosts = mutableSetOf<String>()
        for ((title, uri) in citations(response)) {
            if (venues.size >= MAX_VENUES) break
            val resolved = runCatching { transport.resolve(uri) }.getOrNull() ?: continue
            val host = resolved.toHttpUrlOrNull()?.host.orEmpty()
            // Google's own redirector and its caches are not the venue's page: fetching one proves
            // nothing about a restaurant, and printing one as the source would be a lie.
            if (!usableHost(host)) continue
            if (!hosts.add(host.removePrefix("www."))) continue
            val name = nameFrom(title)
            if (name.length < 3) continue
            venues += Proposal(name = name, url = resolved)
        }
        return venues
    }

    /** Cited pages first; a name a citation already covered is not offered twice. */
    private fun merge(cited: List<Proposal>, named: List<Proposal>, place: String): List<Proposal> {
        val seen = cited.map { PlaceScout.fold(it.name) }.toMutableSet()
        val all = cited.toMutableList()
        for (proposal in named) {
            if (all.size >= MAX_VENUES) break
            if (!seen.add(PlaceScout.fold(proposal.name))) continue
            all += if (proposal.town.isBlank()) proposal.copy(town = place) else proposal
        }
        return all
    }

    /**
     * The prompt. It asks for names and towns, not websites.
     *
     * The first version of this asked only for venues that had their own website, and that is how a
     * town's one wine bar disappeared: a bar with a Google listing and an Instagram does not have
     * one, so the model left it out and the reply said there was nowhere to go. A name is enough to
     * search for, and the search is what finds a page.
     */
    private fun venuesRequest(ask: PlaceScout.Ask, where: String): String {
        val prompt = buildString {
            append("Which ").append(wanted(ask)).append(" in ").append(where)
            append(" are open tonight? Answer with a plain list, one place per line, in exactly ")
            append("this shape and nothing else:\n\n")
            append("Place Name | Town\n\n")
            append("- Use the name locals use, then the town it is in.\n")
            append("- Only real, currently operating places inside ").append(where).append(".\n")
            append("- Include a wine bar even when it has no website of its own.\n")
            append("- No links, no numbering, no sentences, no round-ups or best-of lists.\n")
            append("- If there is nothing worth listing, answer with nothing at all.")
        }
        return request(prompt)
    }

    /** The prompt for the nearest town with somewhere worth the drive. */
    private fun nearbyRequest(ask: PlaceScout.Ask, where: String): String {
        val prompt = buildString {
            append("There is nowhere in ").append(where).append(" I would send someone tonight for ")
            append(wanted(ask)).append(". Name the nearest town or suburb within a 20-minute drive ")
            append("that has one, and one place there. Answer with a plain list, one line per town, ")
            append("nearest first, in exactly this shape:\n\n")
            append("Town, State | Place Name\n\n")
            append("- At most ").append(MAX_TOWNS).append(" towns.\n")
            append("- Only real, currently open places.\n")
            append("- No links, no numbering, no commentary.")
        }
        return request(prompt)
    }

    private fun wanted(ask: PlaceScout.Ask): String = when (ask.kind) {
        PlaceScout.Ask.Kind.BAR -> "wine bars"
        PlaceScout.Ask.Kind.SHOP -> "wine shops"
        PlaceScout.Ask.Kind.RESTAURANT -> "restaurants with a serious wine list"
        PlaceScout.Ask.Kind.ANY -> "wine bars and restaurants with a serious wine list"
    }

    private fun request(prompt: String): String = buildJsonObject {
        putJsonArray("contents") {
            addJsonObject {
                put("role", "user")
                putJsonArray("parts") { addJsonObject { put("text", prompt) } }
            }
        }
        // The tool that makes this live: without it the model answers from memory, which is the
        // failure this feature exists to prevent.
        putJsonArray("tools") { addJsonObject { putJsonObject("google_search") {} } }
        putJsonObject("generationConfig") {
            put("temperature", 0.2)
            put("maxOutputTokens", 500)
        }
    }.toString()

    private suspend fun call(body: String): String? =
        runCatching { transport.post(endpoint(), key, body) }.getOrNull()

    private fun endpoint(): String =
        "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"

    companion object {
        /**
         * Flash-Lite, because this is one search and one list — not a reasoning step — and it is the
         * cheap tier whose grounded prompts are free for the first few hundred a day.
         */
        const val MODEL = "gemini-2.5-flash-lite"

        /** Enough candidates to lose a few to closures; more than six is a longer wait, not a better list. */
        const val MAX_VENUES = 8

        /** One town over is the answer; two is a road trip and three is a directory. */
        const val MAX_TOWNS = 3

        /**
         * The (title, uri) pairs Google cited, in the order it ranked them.
         *
         * A response with no grounding is the model answering from memory; it is cited evidence of
         * nothing. A name from the answer text still has to be confirmed by a search, so it can
         * become a venue without a citation — but never without a page.
         */
        fun citations(response: String): List<Pair<String, String>> = runCatching {
            val candidate = Json.parseToJsonElement(response).jsonObject["candidates"]
                ?.jsonArray?.firstOrNull()?.jsonObject ?: return emptyList()
            val chunks = candidate["groundingMetadata"]?.jsonObject?.get("groundingChunks")
                ?.jsonArray ?: return emptyList()
            chunks.mapNotNull { chunk ->
                val web = chunk.jsonObject["web"]?.jsonObject ?: return@mapNotNull null
                val uri = web["uri"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (uri.isBlank()) return@mapNotNull null
                val title = web["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
                title to uri
            }
        }.getOrDefault(emptyList())

        /** The visible answer of the first candidate, or "" when the response is not one. */
        fun answerText(response: String): String = runCatching {
            Json.parseToJsonElement(response).jsonObject["candidates"]?.jsonArray
                ?.firstOrNull()?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray
                ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
                ?.joinToString("\n")
                .orEmpty()
        }.getOrDefault("")

        /**
         * The `Name | Town` lines of an answer, in order.
         *
         * The separator is the rule, not a nicety. A model that answers in prose — "Try Le Pigeon
         * and Nostrana" — is answering from memory, and a sentence read as a list would invent one
         * place called "Try Le Pigeon and Nostrana". So a line without a separator is not a name at
         * all; a line with one is a candidate that still has to be confirmed by a page.
         */
        fun list(response: String): List<Proposal> = runCatching {
            answerText(response).lineSequence().mapNotNull { lineOf(it) }.toList()
        }.getOrDefault(emptyList())

        /** One `Name | Town` line, or null when the line is not that shape. */
        fun lineOf(raw: String): Proposal? {
            val line = raw.trim()
                .replace(Regex("^[-*•\\s]*\\d{1,2}[.)]\\s*"), "")
                .replace(Regex("^[-*•\\s]+"), "")
                .trim()
            if (line.length !in 3..140) return null
            if (line.contains("http", ignoreCase = true)) return null
            val pair = line.split('|').let { parts ->
                when {
                    parts.size >= 2 -> parts[0] to parts[1]
                    else -> {
                        val dashed = Regex("\\s+[—–-]\\s+").split(line, limit = 2)
                        if (dashed.size == 2) dashed[0] to dashed[1] else return null
                    }
                }
            }
            val name = clean(pair.first, 60)
            if (name.length < 2) return null
            if (name.split(Regex("\\s+")).size > 8) return null
            if (NOT_A_LIST.containsMatchIn(name)) return null
            if (name.count { it.isLetter() } < 2) return null
            val town = clean(pair.second, 50).removePrefix("in ").trim()
            return Proposal(name = name, town = town)
        }

        /** Trimmed, unquoted, single-spaced, and cut to [max] characters on a word boundary. */
        private fun clean(raw: String, max: Int): String {
            val text = raw.replace(Regex("\\s+"), " ").trim()
                .trim('"', '\'', '.', ',', ':', '-', '–', '—', ' ')
                .trim()
            if (text.length <= max) return text
            return text.take(max).substringBeforeLast(' ').trim()
        }

        /** A title that is an article, a guide or a menu rather than one place. */
        private val NOT_A_LIST = Regex(
            "(?i)\\b(best|top|guides?|lists?|round-?up|where to|reviews?|articles?|news|blog)\\b"
        )

        /**
         * The venue's name out of a page title.
         *
         * Grounding titles are the page's `<title>`, so they carry the site's own suffix: "Stem Wine
         * Bar PDX | Vietnamese Food & Wine on N Mississippi" is the bar, not the page. The leading
         * segment is taken, since that is where a business names itself.
         */
        fun nameFrom(title: String): String {
            val leading = title.split(" | ", " — ", " – ", " - ", " :: ").firstOrNull().orEmpty().trim()
            return (leading.ifBlank { title }).trim().trimEnd('.', ',')
        }

        /** False for Google's redirector, its caches, and the social pages a citation can land on. */
        fun usableHost(host: String): Boolean {
            if (host.isBlank()) return false
            val h = host.lowercase()
            return !(h.contains("google") || h.contains("vertexaisearch") || h.contains("gstatic") ||
                h.contains("googleusercontent") || h.contains("youtube.") || h.contains("facebook.") ||
                h.contains("instagram.") || h.contains("tripadvisor.") || h.contains("yelp."))
        }
    }
}

/** The real network: one POST, and one redirect followed to its end. */
class HttpTransport : GeminiGrounding.Transport {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val json = "application/json; charset=utf-8".toMediaType()

    override suspend fun post(url: String, key: String, body: String): String? =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .header("x-goog-api-key", key)
                .post(body.toRequestBody(json))
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                response.body?.string()
            }
        }

    override suspend fun resolve(url: String): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("Accept", "text/html").build()
        http.newCall(request).execute().use { response ->
            // The final address after redirects: the venue's own host, which is what gets printed.
            response.request.url.toString().takeIf { response.isSuccessful || response.isRedirect }
        }
    }
}
