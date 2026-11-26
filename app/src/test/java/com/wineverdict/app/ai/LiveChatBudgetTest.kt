package com.wineverdict.app.ai

import com.wineverdict.app.data.PalateFile
import com.wineverdict.app.data.PalateProfile
import com.wineverdict.app.data.QuickStart
import com.wineverdict.app.model.CellarData
import com.wineverdict.app.model.ChatMessage
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
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Live measurement of the chat request on Groq's free tier: what the prompt actually costs, and
 * whether 2,000 tokens of history leaves room for the answer inside an 8,000-token minute.
 *
 * The body is the app's own — [chatPayload] with the real system prompt and the real window from
 * [chatHistory] — so the numbers describe the shipped request, not a model of it. An instrument,
 * not a regression test: it prints, it asserts nothing. Skipped unless a Groq key is present; the
 * key is never printed.
 *
 * Key lookup, in order: `-DgroqKeyFile=…`, `GROQ_KEY_FILE`, `~/.freebuff/groq.txt`.
 */
class LiveChatBudgetTest {

    private val model = AiProvider.GROQ.defaultAdviceModels.first()
    private val endpoint = "${AiProvider.GROQ.defaultBaseUrl}/chat/completions"
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private data class Usage(
        val code: Int,
        val finish: String,
        val prompt: Int,
        val completion: Int,
        val reasoning: Int,
        val remainingTokens: String,
        val remainingRequests: String,
        val answer: String
    )

    private fun keyFile(): File? = sequenceOf(
        System.getProperty("groqKeyFile"),
        System.getenv("GROQ_KEY_FILE"),
        System.getProperty("user.home") + "/.freebuff/groq.txt",
        System.getProperty("user.home") + "\\.freebuff\\groq.txt"
    ).filterNotNull()
        .map { File(it) }
        .firstOrNull { it.isFile && it.length() > 0 }

    private fun post(payload: String, key: String): Usage {
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
            val choice = root?.get("choices")?.let {
                runCatching { it.jsonObject }.getOrNull()
            }
            return Usage(
                code = res.code,
                finish = choice?.get("finish_reason")?.jsonPrimitive?.contentOrNull.orEmpty(),
                prompt = root?.get("usage")?.jsonObject?.get("prompt_tokens")
                    ?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                completion = root?.get("usage")?.jsonObject?.get("completion_tokens")
                    ?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                reasoning = root?.get("usage")?.jsonObject?.get("completion_tokens_details")
                    ?.jsonObject?.get("reasoning_tokens")?.jsonPrimitive?.contentOrNull
                    ?.toIntOrNull() ?: 0,
                remainingTokens = res.header("x-ratelimit-remaining-tokens").orEmpty(),
                remainingRequests = res.header("x-ratelimit-remaining-requests").orEmpty(),
                answer = root?.get("choices")?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
                    .orEmpty()
            )
        }
    }

    private fun profile() = PalateProfile(
        answers = mapOf(
            QuickStart.Q_POURS to listOf(QuickStart.POUR_RED),
            QuickStart.Q_STYLE to listOf(QuickStart.STYLE_SMOOTH),
            QuickStart.Q_SWEET to listOf(QuickStart.SWEET_NONE),
            QuickStart.Q_BUDGET to listOf(QuickStart.BUDGET_20_35),
            QuickStart.Q_AVOID to listOf(QuickStart.AVOID_OAK, QuickStart.AVOID_STRONG),
            QuickStart.Q_ADVENTURE to listOf(QuickStart.ADVENTURE_SURPRISE)
        )
    )

    /** A long conversation of the shape a real chat has: alternating turns of a few sentences. */
    private fun transcript(turns: Int): List<ChatMessage> {
        val text = "I opened the Rioja last night and it was softer than I expected, " +
            "with the vanilla from the oak sitting behind the fruit rather than in front of it. " +
            "We had it with lamb and it worked, though I would go for something with more acid " +
            "next time if the dish is richer than that."
        return (1..turns).map {
            ChatMessage(
                id = "m$it",
                role = if (it % 2 == 1) "user" else "assistant",
                text = if (it % 2 == 1) "A thought about the last bottle: $text" else "Noted. $text"
            )
        }
    }

    private fun measure(label: String, digestLimit: Int, historyBudget: Int, key: String) {
        val bottles = fixtureWines()
        assumeTrue("fixture did not parse", bottles.size >= 20)
        val cellar = CellarData(wines = bottles)
        val system = Prompts.chat(
            PalateFile.brief(profile()),
            cellar,
            "USD",
            rules = null,
            digestLimit = digestLimit
        )
        // Long enough that the wide (8,000-token) window fills: the first run used 31 messages and
        // carried 2,300 tokens, so it never exercised the budget it was meant to test.
        val all = transcript(turns = 120) + ChatMessage(
            id = "ask",
            role = "user",
            text = QUESTION
        )
        val window = chatHistory(all, budgetTokens = historyBudget)
        val estimated = RateLimiter.estimateTokens(
            promptChars = system.length + window.sumOf { it.text.length },
            images = 0,
            maxTokens = 1_600
        )
        val target = Target(
            provider = AiProvider.GROQ,
            baseUrl = AiProvider.GROQ.defaultBaseUrl,
            key = key,
            model = model
        )
        val payload = chatPayload(
            target = target,
            system = system,
            turns = window,
            images = emptyList(),
            jsonMode = false,
            thinking = Thinking.MEDIUM,
            temperature = 0.5,
            maxTokens = 1_600
        ).toString()
        val usage = post(payload, key)
        println(
            "LIVE chat/$label: digest=$digestLimit history=$historyBudget tokens → kept " +
                "${window.size} of ${all.size} messages, ${window.sumOf { it.text.length }} chars"
        )
        println(
            "LIVE chat/$label: system ${system.length} chars · estimated prompt " +
                "$estimated tok (max_tokens 1600 included) · body ${payload.length} chars"
        )
        println(
            "LIVE chat/$label: http=${usage.code} finish=${usage.finish} " +
                "prompt=${usage.prompt} completion=${usage.completion} " +
                "reasoning=${usage.reasoning} total=${usage.prompt + usage.completion}"
        )
        println(
            "LIVE chat/$label: groq remaining tokens=${usage.remainingTokens} " +
                "requests=${usage.remainingRequests} · answer ${usage.answer.length} chars"
        )
    }

    @Test
    fun thinBudgetOnGroq() {
        val file = keyFile()
        assumeTrue("no Groq key file found; live prompts are skipped", file != null)
        val key = checkNotNull(file).readText().trim()
        measure("thin", digestLimit = 55, historyBudget = 2_000, key = key)
    }

    /**
     * The wide budget on the same tier, to show what it costs there: it is meant for Gemini's
     * 250,000-token minute, so this is expected to strain an 8,000-token one.
     */
    @Test
    fun wideBudgetOnGroq() {
        val file = keyFile()
        assumeTrue("no Groq key file found; live prompts are skipped", file != null)
        val key = checkNotNull(file).readText().trim()
        measure("wide", digestLimit = 140, historyBudget = 8_000, key = key)
    }

    /**
     * The rescue path, measured end to end: an oversized body, the provider's real 413, then
     * [leanPayload] — the question alone — and what that actually costs and returns.
     */
    @Test
    fun rescueAfterA413() {
        val file = keyFile()
        assumeTrue("no Groq key file found; live prompts are skipped", file != null)
        val key = checkNotNull(file).readText().trim()
        val bottles = fixtureWines()
        assumeTrue("fixture did not parse", bottles.size >= 20)
        val system = Prompts.chat(
            PalateFile.brief(profile()),
            CellarData(wines = bottles),
            "USD",
            rules = null,
            digestLimit = 140
        )
        // Deliberately far past the minute: the point is to be refused, not to be realistic.
        val turns = transcript(turns = 200) +
            ChatMessage(id = "ask", role = "user", text = QUESTION)
        val target = Target(
            provider = AiProvider.GROQ,
            baseUrl = AiProvider.GROQ.defaultBaseUrl,
            key = key,
            model = model
        )
        val oversized = chatPayload(
            target = target,
            system = system,
            turns = turns,
            images = emptyList(),
            jsonMode = false,
            thinking = Thinking.MEDIUM,
            temperature = 0.5,
            maxTokens = 1_600
        )
        println(
            "LIVE rescue: oversized ${oversized.length} chars, ${turns.size} turns, estimated " +
                RateLimiter.estimateTokens(
                    promptChars = system.length + turns.sumOf { it.text.length },
                    images = 0,
                    maxTokens = 1_600
                ) + " tok"
        )
        val refused = post(oversized, key)
        println("LIVE rescue: oversized http=${refused.code} (413 expected)")

        val lean = leanPayload(target, system, turns, emptyList(), 0.5, 1_600)
        val rescued = post(lean, key)
        println("LIVE rescue: lean body ${lean.length} chars, newest turn only")
        println(
            "LIVE rescue: lean http=${rescued.code} finish=${rescued.finish} " +
                "prompt=${rescued.prompt} completion=${rescued.completion} " +
                "reasoning=${rescued.reasoning} total=${rescued.prompt + rescued.completion}"
        )
        println(
            "LIVE rescue: remaining tokens=${rescued.remainingTokens} " +
                "answer ${rescued.answer.length} chars"
        )
        if (rescued.answer.isNotEmpty()) {
            println("LIVE rescue first: ${rescued.answer.take(200).replace("\n", " ⏎ ")}")
        }
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

    /** The 26-wine fixture as owned bottles, so the digest has something real to summarise. */
    private fun fixtureWines(): List<Wine> {
        val stream = javaClass.getResourceAsStream("/lists/spanish_list_26.txt") ?: return emptyList()
        val lines = stream.bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() }
        var type = WineType.SPARKLING
        val out = mutableListOf<Wine>()
        for (line in lines) {
            if (!line.contains(" | ")) {
                type = typeOf(line)
                continue
            }
            val bits = line.split("|").map { it.trim() }
            if (bits.size < 3) continue
            val base = bits[0].replace(Regex("\\([^)]*\\)"), "").trim()
            val segments = base.split(',').map { it.trim() }
            val head = segments.first()
            val place = segments.drop(1).joinToString(", ")
            val year = Regex("\\b(19|20)\\d{2}\\b").find(place)?.value?.toIntOrNull()
            val region = place.replace(Regex("\\b(19|20)\\d{2}\\b"), "").trim().trim(',').trim()
            val grapes = Regex("\\(([^)]*)\\)").find(bits[0])?.groupValues?.get(1)
                ?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
            val words = head.split(' ').filter { it.isNotBlank() }
            out += Wine(
                producer = words.take(2).joinToString(" "),
                name = words.drop(2).joinToString(" ").ifBlank { head },
                vintage = year,
                type = type,
                region = region,
                country = "Spain",
                grapes = grapes,
                price = bits[2].toDoubleOrNull(),
                quantity = 1 + out.size % 3,
                currency = "USD"
            )
        }
        return out
    }

    private companion object {
        const val QUESTION = "What should I open with a roast chicken tonight?"
    }
}
