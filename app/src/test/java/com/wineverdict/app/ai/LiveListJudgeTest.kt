package com.wineverdict.app.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Live measurements of the judgement prompt on the 26-wine fixture. An instrument, not a
 * regression test: it prints what the provider actually returns so a claim about truncation can be
 * checked rather than inferred. Skipped unless a Groq key is present; the key is never printed.
 * Excluded from the default test task — run with `-Plive`.
 */
class LiveListJudgeTest {

    private fun setup(): Pair<String, List<ScannedLabel>> {
        val bottles = LiveGroq.bottles()
        assumeTrue("fixture did not parse", bottles.size >= 20)
        val brief = LiveGroq.brief()
        val system = Prompts.listJudge(brief, "USD", bottles)
        println("LIVE fixture: ${bottles.size} bottles · brief ${brief.length} chars · prompt ${system.length} chars")
        return system to bottles
    }

    /**
     * A raw body rather than [chatPayload]: these cases are about the fields themselves, so they
     * have to be set here and not through the app's own mapping.
     */
    private fun call(system: String, turn: String, maxTokens: Int, effort: String?): LiveReply {
        val key = checkNotNull(LiveGroq.key())
        val messages = buildJsonArray {
            addJsonObject { put("role", "system"); put("content", system) }
            addJsonObject { put("role", "user"); put("content", turn) }
        }
        val payload = buildJsonObject {
            put("model", LiveGroq.model)
            put("messages", messages)
            put("temperature", 0.3)
            put("max_tokens", maxTokens)
            effort?.let { put("reasoning_effort", it) }
        }.toString()
        return LiveGroq.post(payload, key)
    }

    private fun report(label: String, r: LiveReply, bottles: List<ScannedLabel>) {
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
        assumeTrue("no Groq key file found; live prompts are skipped", LiveGroq.key() != null)
        val (system, bottles) = setup()
        println("LIVE shipped settings: max_tokens=800, reasoning_effort=none (Thinking.OFF)")
        var r = call(system, TURN, maxTokens = 800, effort = "none")
        if (r.code == 400) {
            println("LIVE 400: reasoning_effort=none is rejected; retrying without the field, as the app did")
            r = call(system, TURN, maxTokens = 800, effort = null)
        }
        report("shipped", r, bottles)
    }

    @Test
    fun atTheAdviceBudget() {
        assumeTrue("no Groq key file found; live prompts are skipped", LiveGroq.key() != null)
        val (system, bottles) = setup()
        println("LIVE advice settings: max_tokens=4000, no reasoning field")
        report("advice", call(system, TURN, maxTokens = 4_000, effort = null), bottles)
    }

    /** What the app now sends: OFF maps to "low", and the budget carries room for reasoning. */
    @Test
    fun atTheShippedBudgetAfterTheFix() {
        assumeTrue("no Groq key file found; live prompts are skipped", LiveGroq.key() != null)
        val (system, bottles) = setup()
        println("LIVE fixed settings: reasoning_effort=low, max_tokens=1400 (800 + 600 reserve)")
        report("fixed", call(system, TURN, maxTokens = 1_400, effort = "low"), bottles)
    }

    private companion object {
        const val TURN = "Rank these wines against my taste and explain each place."
    }
}
