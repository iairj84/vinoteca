package com.vinoteca.app.ai

import com.vinoteca.app.data.SettingsStore
import com.vinoteca.app.model.ChatMessage
import com.vinoteca.app.model.Wine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
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

/** Published notes for one bottle, with the pages they were read from. */
data class SourcedNotes(
    val notes: String,
    val tags: List<String>,
    val sources: List<String>,
    val caveat: String = "",
    /** The ABV a page printed for this bottling, when one did. Never estimated. */
    val alcohol: Double? = null
)

private data class Target(
    val provider: AiProvider,
    val baseUrl: String,
    val key: String,
    val model: String
)

/**
 * One client for every OpenAI-compatible provider Vinoteca can use.
 *
 * A request with no explicit provider walks [SettingsStore.configuredProviders] — primary first —
 * and returns the first answer. A caller that names a provider (the scan queue does, so it can
 * spread frames across several) gets exactly that one, and handles the retry itself.
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

    suspend fun complete(
        system: String,
        turns: List<ChatMessage>,
        imageBase64: String? = null,
        imagesBase64: List<String> = emptyList(),
        vision: Boolean = false,
        jsonMode: Boolean = false,
        thinking: Thinking = Thinking.OFF,
        temperature: Double = 0.4,
        maxTokens: Int = 1400,
        provider: AiProvider? = null,
        model: String? = null,
        onBudgetWait: ((Long) -> Unit)? = null
    ): String = withContext(Dispatchers.IO) {
        val images = buildList {
            imageBase64?.takeIf { it.isNotBlank() }?.let { add(it) }
            imagesBase64.filter { it.isNotBlank() }.forEach { add(it) }
        }
        val targets = resolveTargets(provider, model, vision)
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
                return@withContext attempt(
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
        TAGS: tag, tag, tag
        SOURCES: url url
        CAVEAT: one sentence

        - MATCH is yes only when the text describes this producer and this cuvée. Another vintage
          of the same wine is acceptable when CAVEAT says which one.
        - NOTE is 2 to 4 sentences taken from the text: how it smells, tastes and feels, and how it
          drinks now. Paraphrase; never copy a sentence word for word.
        - ABV is the alcohol figure the text prints for this bottling (14.1 from "14.1% vol"). Leave
          it empty when no page states it: never estimate it from the grape or the region.
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
            return SourcedNotes(
                notes = "",
                tags = emptyList(),
                sources = emptyList(),
                caveat = caveat.ifBlank {
                    if (matchLine == "no") "No page found described this bottling." else ""
                }
            )
        }
        return SourcedNotes(
            notes = note,
            tags = tags,
            sources = cited.ifEmpty { known.take(2) },
            caveat = caveat,
            alcohol = abv
        )
    }

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
            alcohol = alcohol
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
        vision: Boolean
    ): List<Target> {
        val providers = if (provider != null) listOf(provider) else settings.configuredProviders
        return providers.mapNotNull { candidate ->
            val key = settings.key(candidate)
            if (key.isBlank()) return@mapNotNull null
            val chosen = model?.takeIf { provider != null && it.isNotBlank() }
                ?: if (vision) settings.visionModel(candidate) else settings.textModel(candidate)
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
            payload(target, system, turns, images, jsonMode, thinking, temperature, maxTokens)
        )
        if (!response.isSuccessful && response.code == 400) {
            // Some models reject response_format or reasoning_effort, and take at most a few
            // images. Retry with the plainest request that still carries a photo.
            response.close()
            response = post(
                target,
                payload(target, system, turns, images.take(1), false, null, temperature, maxTokens)
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

    private fun payload(
        target: Target,
        system: String,
        turns: List<ChatMessage>,
        images: List<String>,
        jsonMode: Boolean,
        thinking: Thinking?,
        temperature: Double,
        maxTokens: Int
    ): String {
        val messages = buildJsonArray {
            addJsonObject {
                put("role", "system")
                put("content", system)
            }
            turns.forEachIndexed { index, turn ->
                val attachImages = images.isNotEmpty() && index == turns.lastIndex && turn.role == "user"
                if (attachImages) {
                    addJsonObject {
                        put("role", "user")
                        putJsonArray("content") {
                            addJsonObject {
                                put("type", "text")
                                put("text", turn.text.ifBlank { "What can you tell me about this?" })
                            }
                            images.forEach { base64 ->
                                addJsonObject {
                                    put("type", "image_url")
                                    putJsonObject("image_url") {
                                        put("url", "data:image/jpeg;base64,$base64")
                                    }
                                }
                            }
                        }
                    }
                } else {
                    addJsonObject {
                        put("role", turn.role)
                        put("content", turn.text)
                    }
                }
            }
        }
        val root = buildJsonObject {
            put("model", target.model)
            put("messages", messages)
            put("temperature", temperature)
            put("max_tokens", maxTokens)
            if (jsonMode) {
                putJsonObject("response_format") { put("type", "json_object") }
            }
            thinking?.let { level ->
                thinkingValue(target.provider, level)?.let { put("reasoning_effort", it) }
            }
        }
        return root.toString()
    }

    /**
     * JSON answers want the model's deliberation turned down: it is slower, costs output tokens,
     * and occasionally talks itself out of the schema. Groq can switch thinking off; Gemini's
     * compatibility layer cannot, so it gets the lowest level it accepts.
     */
    private fun thinkingValue(provider: AiProvider, thinking: Thinking): String? = when (provider) {
        AiProvider.GROQ -> when (thinking) {
            Thinking.OFF -> "none"
            Thinking.LOW -> "low"
            Thinking.MEDIUM -> "medium"
        }
        AiProvider.GEMINI -> when (thinking) {
            Thinking.OFF, Thinking.LOW -> "low"
            Thinking.MEDIUM -> "medium"
        }
    }

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

    private fun parseContent(provider: AiProvider, body: String): String {
        val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: throw AiException("${provider.label} returned something that is not JSON.")
        val message = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
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