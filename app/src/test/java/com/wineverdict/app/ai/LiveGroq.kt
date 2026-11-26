package com.wineverdict.app.ai

import com.wineverdict.app.data.PalateFile
import com.wineverdict.app.data.PalateProfile
import com.wineverdict.app.data.QuickStart
import com.wineverdict.app.model.CellarData
import com.wineverdict.app.model.Wine
import com.wineverdict.app.model.WineType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/** What a provider answered, with the counts it reported. */
internal data class LiveReply(
    val code: Int,
    val finish: String,
    val content: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val reasoningTokens: Int,
    val remainingTokens: String = "",
    val remainingRequests: String = ""
) {
    val totalTokens: Int get() = promptTokens + completionTokens
}

/**
 * The Groq plumbing the live instruments share: the key file, one POST, the 26-wine fixture and the
 * palate profile the prompts are built from.
 *
 * These classes are instruments, not regression tests — they assert nothing and print what the
 * provider actually returned. They are skipped unless a key is present, and the default test task
 * excludes them because each one spends real free-tier quota. Run them deliberately:
 * `./gradlew :app:testDebugUnitTest -Plive`.
 */
internal object LiveGroq {

    val model: String = AiProvider.GROQ.defaultAdviceModels.first()

    private val endpoint = "${AiProvider.GROQ.defaultBaseUrl}/chat/completions"
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /**
     * Key lookup, in order: `-DgroqKeyFile=…`, `GROQ_KEY_FILE`, `~/.freebuff/groq.txt`.
     *
     * The key is read here and nowhere else, and is never printed: it goes into the Authorization
     * header and stops there.
     */
    private fun keyFile(): File? = sequenceOf(
        System.getProperty("groqKeyFile"),
        System.getenv("GROQ_KEY_FILE"),
        System.getProperty("user.home") + "/.freebuff/groq.txt",
        System.getProperty("user.home") + "\\.freebuff\\groq.txt"
    ).filterNotNull()
        .map { File(it) }
        .firstOrNull { it.isFile && it.length() > 0 }

    fun key(): String? = keyFile()?.readText()?.trim()?.takeIf { it.isNotBlank() }

    fun target(key: String) = Target(
        provider = AiProvider.GROQ,
        baseUrl = AiProvider.GROQ.defaultBaseUrl,
        key = key,
        model = model
    )

    fun post(payload: String, key: String): LiveReply {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .callTimeout(200, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer $key")
            .post(payload.toRequestBody(jsonMedia))
            .build()
        client.newCall(request).execute().use { res ->
            val body = res.body?.string().orEmpty()
            val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            val choice = root?.get("choices")?.jsonArray?.firstOrNull()?.jsonObject
            val message = choice?.get("message")?.jsonObject
            val usage = root?.get("usage")?.jsonObject
            return LiveReply(
                code = res.code,
                finish = choice?.get("finish_reason")?.jsonPrimitive?.contentOrNull.orEmpty(),
                content = message?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty(),
                promptTokens = usage?.get("prompt_tokens")?.jsonPrimitive?.contentOrNull
                    ?.toIntOrNull() ?: 0,
                completionTokens = usage?.get("completion_tokens")?.jsonPrimitive?.contentOrNull
                    ?.toIntOrNull() ?: 0,
                reasoningTokens = usage?.get("completion_tokens_details")?.jsonObject
                    ?.get("reasoning_tokens")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                remainingTokens = res.header("x-ratelimit-remaining-tokens").orEmpty(),
                remainingRequests = res.header("x-ratelimit-remaining-requests").orEmpty()
            )
        }
    }

    fun profile() = PalateProfile(
        answers = mapOf(
            QuickStart.Q_POURS to listOf(QuickStart.POUR_RED),
            QuickStart.Q_STYLE to listOf(QuickStart.STYLE_SMOOTH),
            QuickStart.Q_SWEET to listOf(QuickStart.SWEET_NONE),
            QuickStart.Q_BUDGET to listOf(QuickStart.BUDGET_20_35),
            QuickStart.Q_AVOID to listOf(QuickStart.AVOID_OAK, QuickStart.AVOID_STRONG),
            QuickStart.Q_ADVENTURE to listOf(QuickStart.ADVENTURE_SURPRISE)
        )
    )

    fun brief(): String = PalateFile.brief(profile())

    /** The chat system prompt for a digest size, built exactly as the screen builds it. */
    fun chatSystem(digestLimit: Int): String = Prompts.chat(
        brief(),
        CellarData(wines = wines()),
        "USD",
        rules = null,
        digestLimit = digestLimit
    )

    /** The 26-wine fixture as scanned labels, one section per wine type. */
    fun bottles(): List<ScannedLabel> {
        val stream = javaClass.getResourceAsStream("/lists/spanish_list_26.txt") ?: return emptyList()
        val lines = stream.bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() }
        var type = WineType.SPARKLING
        val out = mutableListOf<ScannedLabel>()
        for (line in lines) {
            if (!line.contains(" | ")) {
                type = typeOf(line)
                continue
            }
            val bits = line.split("|").map { it.trim() }
            if (bits.size < 3) continue
            val glass = bits[1].toDoubleOrNull()
            val bottle = bits[2].toDoubleOrNull()
            val namePart = bits[0]
            val grapes = Regex("\\(([^)]*)\\)").find(namePart)?.groupValues?.get(1)
                ?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
            val base = namePart.replace(Regex("\\([^)]*\\)"), "").trim()
            val segments = base.split(',').map { it.trim() }
            val head = segments.first()
            val place = segments.drop(1).joinToString(", ")
            val year = Regex("\\b(19|20)\\d{2}\\b").find(place)?.value?.toIntOrNull()
            val region = place
                .replace(Regex("\\b(19|20)\\d{2}\\b"), "")
                .replace("NV", "").replace("magnum", "")
                .replace(Regex("\\s+"), " ").trim().trim(',').trim()
            // A cuvée is quoted; take the quoted phrase and keep "Castell d'Or" whole by requiring
            // a space before the opening quote. No quote: the first two words are the producer.
            val quoted = Regex("\\s'([^']+)'").find(head)
            val producer: String
            val cuvee: String
            if (quoted != null) {
                producer = head.substring(0, quoted.range.first).trim()
                cuvee = quoted.groupValues[1].trim()
            } else {
                val words = head.split(' ').filter { it.isNotBlank() }
                producer = words.take(2).joinToString(" ")
                cuvee = words.drop(2).joinToString(" ").ifBlank { head }
            }
            out += ScannedLabel(
                wine = Wine(
                    producer = producer,
                    name = cuvee,
                    vintage = year,
                    type = type,
                    region = region,
                    grapes = grapes,
                    price = bottle,
                    currency = "USD"
                ),
                fitScore = null,
                fitReason = "",
                flavorTags = emptyList(),
                priceEstimate = bottle,
                confidence = "medium",
                readNotes = "",
                glassPrice = glass
            )
        }
        return out
    }

    /** The same fixture as owned bottles, so a cellar digest has something real to summarise. */
    fun wines(): List<Wine> = bottles().mapIndexed { index, label ->
        label.wine.copy(quantity = 1 + index % 3)
    }

    private fun typeOf(section: String): WineType = when (section.uppercase()) {
        "SPARKLING" -> WineType.SPARKLING
        "WHITE" -> WineType.WHITE
        "ORANGE" -> WineType.ORANGE
        "ROSADO", "ROSE" -> WineType.ROSE
        "RED" -> WineType.RED
        "SWEET", "DESSERT" -> WineType.DESSERT
        else -> WineType.RED
    }
}
