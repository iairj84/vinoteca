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
 * What comes back is used only as *discovery* — names and the pages Google cited. The research tail
 * is untouched: each cited page is fetched, a closure on it drops the place, a page that never says
 * wine drops it, and a page that does not place it in the asked town drops it. So the model cannot
 * put a restaurant on the screen; it can only propose a page that then has to survive being read.
 *
 * Two things this deliberately does not do:
 *
 *  - it does not read the model's prose. The reply is written by [PlaceScout.answer] from fetched
 *    pages, and a fluent paragraph here would be a second, unchecked source of names.
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

    /**
     * Venues Google's search cited for this question, best first.
     *
     * Empty is a real answer and so is a failed call: the caller falls back to the map, which is the
     * second opinion rather than the only one. Nothing here throws for a network reason.
     */
    suspend fun find(ask: PlaceScout.Ask): List<PlaceScout.Venue> {
        if (key.isBlank()) return emptyList()
        val where = ask.where.trim()
        if (where.isBlank()) return emptyList()
        val body = request(ask, where)
        val response = runCatching { transport.post(endpoint(), key, body) }.getOrNull()
            ?: return emptyList()
        val cited = citations(response)
        if (cited.isEmpty()) return emptyList()

        val venues = mutableListOf<PlaceScout.Venue>()
        val hosts = mutableSetOf<String>()
        for ((title, uri) in cited) {
            if (venues.size >= MAX_VENUES) break
            val resolved = runCatching { transport.resolve(uri) }.getOrNull() ?: continue
            val host = resolved.toHttpUrlOrNull()?.host.orEmpty()
            // Google's own redirector and its caches are not the venue's page: fetching one proves
            // nothing about a restaurant, and printing one as the source would be a lie.
            if (!usableHost(host)) continue
            if (!hosts.add(host.removePrefix("www."))) continue
            val name = nameFrom(title)
            if (name.length < 3) continue
            venues += PlaceScout.Venue(name = name, url = resolved, title = title, snippet = "")
        }
        return venues
    }

    /** The prompt. It asks for websites because a website is what the research can read. */
    private fun request(ask: PlaceScout.Ask, where: String): String {
        val wanted = when (ask.kind) {
            PlaceScout.Ask.Kind.BAR -> "wine bars"
            PlaceScout.Ask.Kind.SHOP -> "wine shops"
            PlaceScout.Ask.Kind.RESTAURANT -> "restaurants with a serious wine list"
            PlaceScout.Ask.Kind.ANY -> "wine bars and restaurants with a serious wine list"
        }
        val prompt = buildString {
            append("Which $wanted in $where are open tonight? ")
            append("Return only real, currently operating venues inside $where, each with its own ")
            append("website. Prefer places known for wine. Exclude anything permanently closed, ")
            append("chains, listicles and directory pages.")
        }
        return buildJsonObject {
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
                put("maxOutputTokens", 400)
            }
        }.toString()
    }

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

        /**
         * The (title, uri) pairs Google cited, in the order it ranked them.
         *
         * A response with no grounding is the model answering from memory; it is treated as no
         * answer, not as a list of places.
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
