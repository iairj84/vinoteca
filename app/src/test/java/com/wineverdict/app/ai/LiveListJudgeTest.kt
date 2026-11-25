package com.wineverdict.app.ai

import com.wineverdict.app.data.PalateFile
import com.wineverdict.app.data.PalateProfile
import com.wineverdict.app.data.QuickStart
import com.wineverdict.app.model.Wine
import com.wineverdict.app.model.WineType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Live measurements of the judgement prompt on the 26-wine fixture. An instrument, not a
 * regression test: it prints what the provider actually returns so a claim about truncation can be
 * checked rather than inferred. Skipped unless a Groq key is present; the key is never printed.
 *
 * Key lookup, in order: `-DgroqKeyFile=…`, `GROQ_KEY_FILE`, `~/.freebuff/groq.txt`.
 */
class LiveListJudgeTest {

    private val model = "openai/gpt-oss-120b"
    private val endpoint = "https://api.groq.com/openai/v1/chat/completions"
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private data class Result(
        val code: Int,
        val finish: String,
        val content: String,
        val reasoning: String,
        val completionTokens: Int,
        val reasoningTokens: Int
    )

    private fun keyFile(): File? = sequenceOf(
        System.getProperty("groqKeyFile"),
        System.getenv("GROQ_KEY_FILE"),
        System.getProperty("user.home") + "/.freebuff/groq.txt",
        System.getProperty("user.home") + "\\.freebuff\\groq.txt"
    ).filterNotNull()
        .map { File(it) }
        .firstOrNull { it.isFile && it.length() > 0 }

    private fun call(system: String, turn: String, maxTokens: Int, reasoning: Boolean): Result {
        val file = checkNotNull(keyFile())
        val key = file.readText().trim()
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .callTimeout(200, TimeUnit.SECONDS)
            .build()
        val messages = buildJsonArray {
            addJsonObject { put("role", "system"); put("content", system) }
            addJsonObject { put("role", "user"); put("content", turn) }
        }
        val payload = buildJsonObject {
            put("model", model)
            put("messages", messages)
            put("temperature", 0.3)
            put("max_tokens", maxTokens)
            if (reasoning) put("reasoning_effort", "none")
        }.toString()
        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer $key")
            .post(payload.toRequestBody(jsonMedia))
            .build()
        client.newCall(request).execute().use { res ->
            val body = res.body?.string().orEmpty()
            if (res.code != 200) {
                println("LIVE http=${res.code} error=${body.take(400)}")
                return Result(res.code, "", "", "", 0, 0)
            }
            val root = Json.parseToJsonElement(body).jsonObject
            val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            val message = choice?.get("message")?.jsonObject
            val usage = root["usage"]?.jsonObject
            return Result(
                code = res.code,
                finish = choice?.get("finish_reason")?.jsonPrimitive?.contentOrNull.orEmpty(),
                content = message?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty(),
                reasoning = message?.get("reasoning")?.jsonPrimitive?.contentOrNull.orEmpty(),
                completionTokens = usage?.get("completion_tokens")?.jsonPrimitive?.contentOrNull
                    ?.toIntOrNull() ?: 0,
                reasoningTokens = usage?.get("completion_tokens_details")?.jsonObject
                    ?.get("reasoning_tokens")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
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

    private fun setup(): Pair<String, List<ScannedLabel>> {
        val bottles = fixtureBottles()
        assumeTrue("fixture did not parse", bottles.size >= 20)
        val brief = PalateFile.brief(profile())
        val system = Prompts.listJudge(brief, "USD", bottles)
        println("LIVE fixture: ${bottles.size} bottles · brief ${brief.length} chars · prompt ${system.length} chars")
        return system to bottles
    }

    private fun report(label: String, r: Result, bottles: List<ScannedLabel>) {
        println(
            "LIVE $label: http=${r.code} finish=${r.finish} completion=${r.completionTokens} " +
                "reasoning=${r.reasoningTokens} answer=${r.content.length} chars"
        )
        if (r.content.isNotEmpty()) {
            println("LIVE $label first: ${r.content.take(200).replace("\n", " ⏎ ")}")
            println("LIVE $label last:  ${r.content.takeLast(200).replace("\n", " ⏎ ")}")
        }
        val placed = ScanParsing.applyJudgement(SceneScan(Scene.LIST, bottles, ""), r.content)
            ?.bottles?.count { it.listRank != null } ?: 0
        println("LIVE $label applyJudgement placed $placed of ${bottles.size}")
    }

    @Test
    fun atTheShippedBudget() {
        assumeTrue("no Groq key file found; live prompts are skipped", keyFile() != null)
        val (system, bottles) = setup()
        println("LIVE shipped settings: max_tokens=800, reasoning_effort=none (Thinking.OFF)")
        var r = call(system, TURN, maxTokens = 800, reasoning = true)
        if (r.code == 400) {
            println("LIVE 400: reasoning_effort=none is rejected; retrying without the field, as the app does")
            r = call(system, TURN, maxTokens = 800, reasoning = false)
        }
        report("shipped", r, bottles)
    }

    @Test
    fun atTheAdviceBudget() {
        assumeTrue("no Groq key file found; live prompts are skipped", keyFile() != null)
        val (system, bottles) = setup()
        println("LIVE advice settings: max_tokens=4000, no reasoning field")
        val r = call(system, TURN, maxTokens = 4_000, reasoning = false)
        report("advice", r, bottles)
    }

    private companion object {
        const val TURN = "Rank these wines against my taste and explain each place."
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

    private fun fixtureBottles(): List<ScannedLabel> {
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
}
