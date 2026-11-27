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


    /** Search results in rank order. Throws only when the search itself could not be reached. */
    suspend fun search(query: String): List<Hit> = withContext(Dispatchers.IO) {
        val url = "https://lite.duckduckgo.com/lite/?q=" + URLEncoder.encode(query, "UTF-8")
        val html = get(url) ?: throw AiException(
            "The web search did not answer. That is the phone's connection, not the bottle: " +
                "try again when it is back."
        )
        parseHits(html)
    }

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
     * The picture a page offers for itself — its `og:image`, then its Twitter card — downloaded.
     *
     * A bottle added from a search was named by a page, and that page almost always carries the
     * producer's own photograph of the label. Reading it here is what puts a real bottle on the
     * thumbnail instead of a placeholder, for one request on top of the search that was already
     * paid for.
     *
     * [mentions] are the words the page has to carry before its picture is trusted. A photograph of
     * the neighbouring bottling is worse than no photograph, so a search for a bottle the app
     * already holds asks for its producer and its name rather than the first page that answers.
     *
     * Null is a real answer — no card, a page that does not name the wine, a `data:` URI, a
     * response that is not an image, or a picture too big to be worth keeping. Nothing is invented,
     * and nothing is tried twice.
     */
    suspend fun pageImage(pageUrl: String, mentions: List<String> = emptyList()): ByteArray? =
        withContext(Dispatchers.IO) {
            if (!pageUrl.startsWith("http")) return@withContext null
            val html = get(pageUrl).orEmpty()
            if (html.isBlank()) return@withContext null
            if (!mentions(html, mentions)) return@withContext null
            val picture = imageUrl(html, pageUrl) ?: return@withContext null
            download(picture)
        }

    /** Whether the page says these words, ignoring case, accents and punctuation. */
    private fun mentions(html: String, words: List<String>): Boolean {
        val wanted = words.map { fold(it) }.filter { it.length >= 4 }
        if (wanted.isEmpty()) return true
        val page = fold(html)
        return wanted.all { page.contains(it) }
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