package com.wineverdict.app.ai

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Live measurement of the advice prompt on Gemini, over the 26-wine fixture.
 *
 * An instrument, not a regression test: it prints what the provider returned and what
 * [ScanParsing.parseAdvice] made of it, and writes the raw answer to
 * `app/build/live/gemini-advice.txt` so the block can be rendered from a real answer. Skipped
 * unless a Gemini key file is present; the key is never printed. Excluded from the default test
 * task — run with `-Plive`.
 *
 * Gemini answers this request with a 503 ("model experiencing high demand") often enough that a
 * single attempt measures the load rather than the prompt, so it is retried three times.
 */
class LiveGeminiAdviceTest {

    @Test
    fun adviceOnTheTwentySixWineFixture() {
        val key = LiveGemini.key()
        assumeTrue("no Gemini key file found; live prompts are skipped", key != null)
        val bottles = LiveGroq.bottles()
        assumeTrue("fixture did not parse", bottles.size >= 20)

        val system = Prompts.listAdvice(
            brief = LiveGroq.brief(),
            cellarSummary = "",
            currency = "USD",
            bottles = bottles
        )
        println(
            "LIVE gemini advice: ${bottles.size} bottles · prompt ${system.length} chars · " +
                "model ${LiveGemini.model} · max_tokens ${outputBudget(4_000, Thinking.MEDIUM)}"
        )

        var reply: LiveReply? = null
        for (attempt in 1..3) {
            val r = LiveGemini.post(
                LiveGemini.payload(
                    system = system,
                    turn = "What should I order from this list?",
                    maxTokens = 4_000,
                    thinking = Thinking.MEDIUM
                ),
                key!!
            )
            println(
                "LIVE gemini advice attempt $attempt: http=${r.code} finish=${r.finish} " +
                    "answer=${r.content.length} chars prompt=${r.promptTokens} " +
                    "completion=${r.completionTokens}"
            )
            if (r.code == 200 && r.content.isNotBlank()) {
                reply = r
                break
            }
            if (r.code != 503) break
            Thread.sleep(5_000)
        }

        val r = reply ?: run {
            println("LIVE gemini advice: no answer after 3 attempts; nothing parsed")
            return
        }
        println("LIVE gemini advice first: ${r.content.take(200).replace("\n", " ⏎ ")}")
        println("LIVE gemini advice last:  ${r.content.takeLast(200).replace("\n", " ⏎ ")}")

        val read = SceneScan(Scene.LIST, bottles, "")
        val advice = ScanParsing.parseAdvice(r.content, read)
        println(
            "LIVE parsed: picks=${advice?.picks?.size ?: 0} glass=${advice?.glass?.index?.plus(1)} " +
                "skips=${advice?.skips?.size ?: 0} dropped=${advice?.dropped?.size ?: 0}"
        )
        advice?.picks?.forEach { pick ->
            println(
                "LIVE pick ${pick.index + 1}: ${bottles[pick.index].wine.title} — ${pick.headline} " +
                    "(${pick.why.length} chars of why)"
            )
        }
        advice?.dropped?.forEach { println("LIVE dropped: $it") }

        val out = File("build/live/gemini-advice.txt")
        out.parentFile?.mkdirs()
        out.writeText(r.content)
        println("LIVE wrote ${out.absolutePath} (${r.content.length} chars)")
    }
}
