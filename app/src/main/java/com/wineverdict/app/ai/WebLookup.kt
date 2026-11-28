package com.wineverdict.app.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * The app's own web retrieval: one keyless search, then the pages that answer it, stripped to text.
 *
 * Gemini's Search grounding cannot do this on a free key (Google gates it behind billing, so the
 * call comes back 429 with a limit of zero), and Groq has no search at all. Fetching the pages here
 * means the model only ever reports what a real page says, on any provider, for nothing.
 */
/** A bottle's photograph, not a wall of bottles: past this it is not the picture wanted. */
private const val MAX_IMAGE_BYTES = 4_000_000L

class WebLookup {

    data class Hit(val title: String, val url: String, val snippet: String)

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // DuckDuckGo's lite endpoint answers a plain Android browser without JavaScript. A desktop
    // string gets its challenge page instead.
    private val userAgent =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0 Mobile Safari/537.36"

    /** The meta cards a page uses to declare the picture that stands for it. */
    private val IMAGE_CARDS = setOf(
        "og:image",
        "og:image:url",
        "og:image:secure_url",
        "twitter:image",
        "twitter:image:src"
    )

    /** Unmistakable in a picture's own address, whatever it is glued to. */
    private val FURNITURE_ANYWHERE = setOf(
        "logo", "icon", "banner", "sprite", "avatar", "favicon", "placeholder",
        "corkscrew", "tappo", "tappi"
    )

    /** Words that are a thing rather than a bottle, matched whole so "pinot" is not a pin. */
    private val FURNITURE_WORDS = setOf(
        "cork", "header", "footer", "hero", "badge", "social", "payment", "spacer",
        "divider", "capsule", "candle", "vineyard", "medal", "award", "shelf", "newsletter",
        "map", "gift", "menu", "cart", "arrow", "star", "glass", "scene"
    )

    private val WORD_SPLIT = Regex("[^0-9a-z\\u00C0-\\u00FF]+")

    private val JSON_LD = Regex(
        "<script[^>]+application/ld\\+json[^>]*>(.*?)</script>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val JSON_IMAGE = Regex("\"image\"\\s*:\\s*(\\[[^\\]]*\\]|\"[^\"]+\")")
    private val QUOTED = Regex("\"([^\"]+)\"")
    private val ITEMPROP_IMAGE = Regex(
        "<[a-z]+\\s[^>]*itemprop\\s*=\\s*[\"']image[\"'][^>]*>",
        RegexOption.IGNORE_CASE
    )
    private val IMG_TAG = Regex("<img\\s[^>]*>", RegexOption.IGNORE_CASE)
    private val ALT_ATTR = Regex("alt\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)
    private val META_TAG = Regex("<meta\\s[^>]*>", RegexOption.IGNORE_CASE)
    private val META_KEY = Regex(
        "(?:property|name)\\s*=\\s*[\"']([^\"']+)[\"']",
        RegexOption.IGNORE_CASE
    )
    private val META_CONTENT = Regex("content\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
    private val SRC_ATTR = Regex("src\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)


    /**
     * Search results in rank order. Throws when the search could not be reached, or was refused.
     *
     * The refusal matters: after a burst of searches DuckDuckGo answers HTTP 202 with an "anomaly"
     * challenge page, which is a successful response carrying no results. Parsed as results it said
     * "the web had nothing" — a wrong diagnosis that sent the reader looking at the bottle or the
     * town when the truth was a search engine asking for a minute's peace. Place research asks
     * several searches in a row, so this is not a corner case any more.
     */
    suspend fun search(query: String): List<Hit> = withContext(Dispatchers.IO) {
        val url = "https://lite.duckduckgo.com/lite/?q=" + URLEncoder.encode(query, "UTF-8")
        val html = get(url) ?: throw AiException(
            "The web search did not answer. That is the phone's connection, not the bottle: " +
                "try again when it is back."
        )
        if (challenged(html)) throw AiException(
            "The search engine is refusing queries from this connection for a minute — it answers " +
                "that way when it sees a burst. Nothing was guessed in the meantime: ask again in a " +
                "minute and it will look properly.",
            retryable = true
        )
        parseHits(html)
    }


    /**
     * Whether this page is the engine's challenge rather than its results.
     *
     * A real results page always carries `uddg=` links; an anomaly page carries words like anomaly,
     * captcha or "JavaScript is required" and not one result. Both conditions are required, so a
     * legitimate snippet about a security anomaly is not mistaken for a block.
     */
    internal fun challenged(html: String): Boolean =
        !html.contains("uddg=") && CHALLENGE.containsMatchIn(html)

    private val CHALLENGE = Regex(
        "(?i)(anomaly|captcha|unusual traffic|javascript is required|challenge)"
    )

    /** The readable text of one page, up to [limit] characters. Empty when it cannot be used. */
    suspend fun pageText(url: String, limit: Int = 5000): String = withContext(Dispatchers.IO) {
        val html = get(url).orEmpty()
        if (html.isBlank()) return@withContext ""
        val withoutScripts = Regex(
            """(?is)<(script|style|noscript|svg|nav|footer|header|form|aside)[^>]*>.*?</\1>"""
        ).replace(html, " ")
        val text = withoutScripts
            .replace(Regex("(?s)<[^>]+>"), " ")
            .let { decodeEntities(it) }
            .replace(Regex("\\s+"), " ")
            .trim()
        text.take(limit)
    }

    /**
     * The picture a page offers for the bottle it is about, downloaded.
     *
     * [name] and [producer] are what the bottle is called: the page has to say the name, and the
     * pictures it offers are ranked by whether they name the wine too. Its product data comes before
     * its social card, because a shop's card is often the shop's own banner — a 976×291 "Uncork
     * Life" strip was what the first version of this kept as a bottle's photograph. Every picture
     * then has to pass [PictureGate]: one bottle's shape, on a plain field.
     *
     * Null is a real answer — no picture, a page that does not name the wine, a logo, a banner, a
     * lifestyle scene, a cork. Nothing is invented, and nothing is tried twice.
     */
    suspend fun pageImage(pageUrl: String, name: String = "", producer: String = ""): ByteArray? =
        withContext(Dispatchers.IO) {
            if (!pageUrl.startsWith("http")) return@withContext null
            val html = get(pageUrl).orEmpty()
            if (html.isBlank()) return@withContext null
            if (!mentions(html, tokens("$producer $name"))) return@withContext null
            for (candidate in pictureCandidates(html, pageUrl, tokens(name), tokens(producer)).take(8)) {
                val picture = download(candidate) ?: continue
                if (PictureGate.acceptable(picture)) return@withContext picture
            }
            null
        }

    /** Whether the page says these words, ignoring case, accents and punctuation. */
    private fun mentions(html: String, words: List<String>): Boolean {
        val wanted = words.filter { it.length >= 4 }.distinct()
        if (wanted.isEmpty()) return true
        val page = fold(html)
        return wanted.all { page.contains(it) }
    }

    private fun tokens(raw: String): List<String> = raw
        .split(Regex("[^0-9A-Za-z\\u00C0-\\u00FF]+"))
        .map { fold(it) }
        .filter { it.length >= 3 }
        .distinct()

    /**
     * Every picture the page offers, best first.
     *
     * Schema.org's `Product.image` and an `itemprop="image"` come before an `<img>` that names the
     * wine in its address or its alt text, which comes before `og:image`, which comes before the
     * Twitter card. Anything wearing a word that is furniture rather than a bottle — logo, banner,
     * cork, candle, glass — is not a candidate at all.
     */
    internal fun pictureCandidates(
        html: String,
        pageUrl: String,
        name: List<String> = emptyList(),
        producer: List<String> = emptyList()
    ): List<String> {
        val scored = mutableListOf<Pair<Int, String>>()
        for (block in JSON_LD.findAll(html)) {
            val body = block.groupValues[1]
            if (!body.contains("\"Product\"") && !body.contains("\"Wine\"")) continue
            for (image in JSON_IMAGE.findAll(body)) {
                for (quoted in QUOTED.findAll(image.groupValues[1])) {
                    offer(scored, 70, pageUrl, quoted.groupValues[1])
                }
            }
        }
        for (tag in ITEMPROP_IMAGE.findAll(html)) {
            SRC_ATTR.find(tag.value)?.groupValues?.get(1)?.let { offer(scored, 60, pageUrl, it) }
        }

        val named = (name + producer).distinct()
        for (tag in IMG_TAG.findAll(html)) {
            val text = tag.value
            val raw = listOf("src", "data-src", "data-lazy-src", "srcset", "data-srcset")
                .firstNotNullOfOrNull { attribute ->
                    Regex("$attribute\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                        .find(text)?.groupValues?.get(1)
                        ?.substringBefore(",")?.substringBefore(" ")
                } ?: continue
            val alt = ALT_ATTR.find(text)?.groupValues?.get(1).orEmpty()
            if (furniture(raw + " " + alt)) continue
            val folded = fold(raw + " " + alt)
            if (named.any { folded.contains(it) }) offer(scored, 65, pageUrl, raw)
        }

        for (tag in META_TAG.findAll(html)) {
            val text = tag.value
            val key = META_KEY.find(text)?.groupValues?.get(1)?.lowercase() ?: continue
            if (key !in IMAGE_CARDS) continue
            val content = META_CONTENT.find(text)?.groupValues?.get(1)?.trim().orEmpty()
            if (content.isBlank() || furniture(content)) continue
            offer(scored, if (key.startsWith("og:")) 50 else 40, pageUrl, content)
        }

        val seen = mutableSetOf<String>()
        return scored.sortedByDescending { it.first }.map { it.second }.filter { seen.add(it) }
    }

    private fun offer(
        into: MutableList<Pair<Int, String>>,
        score: Int,
        pageUrl: String,
        raw: String
    ) {
        if (raw.startsWith("data:", ignoreCase = true)) return
        resolve(pageUrl, raw)?.let { into += score to it }
    }

    /** A word that means the page's own furniture, or something that is not a bottle. */
    private fun furniture(raw: String): Boolean {
        val low = raw.lowercase()
        if (FURNITURE_ANYWHERE.any { low.contains(it) }) return true
        return WORD_SPLIT.split(low).any { it in FURNITURE_WORDS }
    }

    private fun fold(raw: String): String = java.text.Normalizer
        .normalize(raw.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .filter { it.isLetterOrDigit() }

    /**
     * The card image a page declares, resolved against the page's own address. Null when it
     * declares none, or declares one that is not a picture the app could keep.
     */
    internal fun imageUrl(html: String, pageUrl: String): String? {
        val cards = Regex("""<meta\s[^>]*>""", RegexOption.IGNORE_CASE).findAll(html).mapNotNull { tag ->
            val text = tag.value
            val key = Regex("""(?:property|name)\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(text)?.groupValues?.get(1)?.lowercase() ?: return@mapNotNull null
            if (key !in IMAGE_CARDS) return@mapNotNull null
            val content = Regex("""content\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(text)?.groupValues?.get(1)?.trim().orEmpty()
            if (content.isBlank()) return@mapNotNull null
            key to content
        }.toList()
        // Open Graph first: that is the page's own choice of picture. The Twitter card is the
        // fallback, and between two of the same kind the first one wins, which is the page's order.
        val chosen = cards.firstOrNull { it.first.startsWith("og:") } ?: cards.firstOrNull()
        return chosen?.second?.let { resolve(pageUrl, it) }
    }

    private fun resolve(pageUrl: String, raw: String): String? {
        if (raw.startsWith("data:", ignoreCase = true)) return null
        val resolved = runCatching { java.net.URI(pageUrl).resolve(raw.trim()).toString() }
            .getOrNull()
            ?: return null
        // The app is built against a target that refuses cleartext, so a picture declared on http
        // is asked for over https — where nearly every shop already serves it — rather than lost.
        return resolved.replaceFirst("http://", "https://").takeIf { it.startsWith("https://") }
    }

    private fun download(url: String): ByteArray? = try {
        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", userAgent)
            .addHeader("Accept", "image/*")
            .get()
            .build()
        http.newCall(request).execute().use { res ->
            val body = res.body
            val type = body?.contentType()?.toString().orEmpty()
            if (!res.isSuccessful || body == null || !type.startsWith("image/", ignoreCase = true)) {
                null
            } else if (body.contentLength() > MAX_IMAGE_BYTES) {
                null
            } else {
                body.bytes().takeIf { it.size <= MAX_IMAGE_BYTES }
            }
        }
    } catch (e: IOException) {
        null
    }

    private fun parseHits(html: String): List<Hit> {
        val urls = Regex("""uddg=([^&"']+)""").findAll(html)
            .mapNotNull { match ->
                runCatching { URLDecoder.decode(match.groupValues[1], "UTF-8") }.getOrNull()
            }
            .filter { it.startsWith("http") }
            .distinct()
            .toList()
        val titles = Regex("""class=['"]result-link['"][^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(html)
            .map { decodeEntities(it.groupValues[1].replace(Regex("<[^>]+>"), " ")).trim() }
            .toList()
        val snippets = Regex("""<td class=['"]result-snippet['"]>(.*?)</td>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(html)
            .map { decodeEntities(it.groupValues[1].replace(Regex("<[^>]+>"), " ")).trim() }
            .toList()
        return urls.mapIndexed { index, url ->
            Hit(
                title = titles.getOrNull(index).orEmpty(),
                url = url,
                snippet = snippets.getOrNull(index).orEmpty()
            )
        }
    }

    private fun get(url: String): String? = try {
        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", userAgent)
            .addHeader("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
            .addHeader("Accept-Language", "en;q=0.9,fr;q=0.8")
            .get()
            .build()
        http.newCall(request).execute().use { res ->
            if (!res.isSuccessful) return null
            val bytes = res.body?.bytes() ?: return null
            // Pages are mostly a head, a menu and then the wine; half a megabyte is plenty and
            // keeps a big shop page from eating the phone's memory.
            String(bytes, 0, minOf(bytes.size, 600_000), Charsets.UTF_8)
        }
    } catch (e: IOException) {
        null
    }

    private fun decodeEntities(raw: String): String {
        var text = raw
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&#x27;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&hellip;", "…")
            .replace("&mdash;", "—")
            .replace("&ndash;", "–")
            .replace("&rsquo;", "’")
            .replace("&lsquo;", "‘")
            .replace("&ldquo;", "“")
            .replace("&rdquo;", "”")
        text = Regex("&#(\\d{1,5});").replace(text) { match ->
            match.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: match.value
        }
        return text
    }
}