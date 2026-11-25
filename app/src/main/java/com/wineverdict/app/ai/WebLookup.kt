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