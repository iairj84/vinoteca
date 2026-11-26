package com.wineverdict.app.ai

import com.wineverdict.app.model.CellarData
import com.wineverdict.app.model.ChatMessage
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Live measurement of the chat request on Groq's free tier: what the prompt actually costs, whether
 * 2,000 tokens of history leaves room for the answer inside an 8,000-token minute, and whether the
 * 413 rescue answers.
 *
 * The body is the app's own — [chatPayload] with the real system prompt and the real window from
 * [chatHistory] — so the numbers describe the shipped request, not a model of it. An instrument,
 * not a regression test: it prints, it asserts nothing. Excluded from the default test task — run
 * with `-Plive`.
 */
class LiveChatBudgetTest {

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
        val bottles = LiveGroq.wines()
        assumeTrue("fixture did not parse", bottles.size >= 20)
        val system = Prompts.chat(
            LiveGroq.brief(),
            CellarData(wines = bottles),
            "USD",
            rules = null,
            digestLimit = digestLimit
        )
        // Long enough that the wide (8,000-token) window fills: an earlier run used 31 messages and
        // carried 2,300 tokens, so it never exercised the budget it was meant to test.
        val all = transcript(turns = 120) +
            ChatMessage(id = "ask", role = "user", text = QUESTION)
        val window = chatHistory(all, budgetTokens = historyBudget)
        val estimated = RateLimiter.estimateTokens(
            promptChars = system.length + window.sumOf { it.text.length },
            images = 0,
            maxTokens = 1_600
        )
        val payload = chatPayload(
            target = LiveGroq.target(key),
            system = system,
            turns = window,
            images = emptyList(),
            jsonMode = false,
            thinking = Thinking.MEDIUM,
            temperature = 0.5,
            maxTokens = 1_600
        )
        val reply = LiveGroq.post(payload, key)
        println(
            "LIVE chat/$label: digest=$digestLimit history=$historyBudget tokens → kept " +
                "${window.size} of ${all.size} messages, ${window.sumOf { it.text.length }} chars"
        )
        println(
            "LIVE chat/$label: system ${system.length} chars · estimated prompt " +
                "$estimated tok (max_tokens 1600 included) · body ${payload.length} chars"
        )
        println(
            "LIVE chat/$label: http=${reply.code} finish=${reply.finish} " +
                "prompt=${reply.promptTokens} completion=${reply.completionTokens} " +
                "reasoning=${reply.reasoningTokens} total=${reply.totalTokens}"
        )
        println(
            "LIVE chat/$label: groq remaining tokens=${reply.remainingTokens} " +
                "requests=${reply.remainingRequests} · answer ${reply.content.length} chars"
        )
    }

    @Test
    fun thinBudgetOnGroq() {
        val key = LiveGroq.key()
        assumeTrue("no Groq key file found; live prompts are skipped", key != null)
        measure("thin", digestLimit = 55, historyBudget = 2_000, key = checkNotNull(key))
    }

    /**
     * The wide budget on the same tier, to show what it costs there: it is meant for Gemini's
     * 250,000-token minute, so this is expected to strain an 8,000-token one.
     */
    @Test
    fun wideBudgetOnGroq() {
        val key = LiveGroq.key()
        assumeTrue("no Groq key file found; live prompts are skipped", key != null)
        measure("wide", digestLimit = 140, historyBudget = 8_000, key = checkNotNull(key))
    }

    /**
     * The rescue path, measured end to end: an oversized body, the provider's real 413, then
     * [leanPayload] — the question alone — and what that actually costs and returns.
     */
    @Test
    fun rescueAfterA413() {
        val key = LiveGroq.key()
        assumeTrue("no Groq key file found; live prompts are skipped", key != null)
        val bottles = LiveGroq.wines()
        assumeTrue("fixture did not parse", bottles.size >= 20)
        val system = Prompts.chat(
            LiveGroq.brief(),
            CellarData(wines = bottles),
            "USD",
            rules = null,
            digestLimit = 140
        )
        // Deliberately far past the minute: the point is to be refused, not to be realistic.
        val turns = transcript(turns = 200) +
            ChatMessage(id = "ask", role = "user", text = QUESTION)
        val target = LiveGroq.target(checkNotNull(key))
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
        val refused = LiveGroq.post(oversized, checkNotNull(key))
        println("LIVE rescue: oversized http=${refused.code} (413 expected)")

        val lean = leanPayload(target, system, turns, emptyList(), 0.5, 1_600)
        val rescued = LiveGroq.post(lean, checkNotNull(key))
        println("LIVE rescue: lean body ${lean.length} chars, newest turn only")
        println(
            "LIVE rescue: lean http=${rescued.code} finish=${rescued.finish} " +
                "prompt=${rescued.promptTokens} completion=${rescued.completionTokens} " +
                "reasoning=${rescued.reasoningTokens} total=${rescued.totalTokens}"
        )
        println(
            "LIVE rescue: remaining tokens=${rescued.remainingTokens} " +
                "answer ${rescued.content.length} chars"
        )
        if (rescued.content.isNotEmpty()) {
            println("LIVE rescue first: ${rescued.content.take(200).replace("\n", " ⏎ ")}")
        }
    }

    private companion object {
        const val QUESTION = "What should I open with a roast chicken tonight?"
    }
}
