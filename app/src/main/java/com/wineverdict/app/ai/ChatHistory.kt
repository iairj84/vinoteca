package com.wineverdict.app.ai

import com.wineverdict.app.model.ChatMessage

/** Roughly four characters to a token, which is what the limiter already assumes. */
private const val CHARS_PER_TOKEN = 4

/** Role, separators and the rest of a chat turn's framing. */
private const val MESSAGE_OVERHEAD = 4

/** A minute wide enough to carry a long conversation: Gemini's 250,000 tokens. */
private const val WIDE_MINUTE_TOKENS = 100_000
private const val WIDE_HISTORY_TOKENS = 8_000
private const val THIN_HISTORY_TOKENS = 2_000
private const val WIDE_DIGEST_BOTTLES = 140
private const val THIN_DIGEST_BOTTLES = 55

/** What one chat request may carry. */
internal data class ChatBudget(val historyTokens: Int, val digestLimit: Int)

/**
 * How much to send, given who could answer.
 *
 * Sized from the thinnest provider in the list, not the one that leads. The fallback receives the
 * same body, so a window that only the lead can afford is a window the fallback chokes on: an
 * 8,000-token history is comfortable on Gemini's 250,000-token minute and a 413 on Groq's 8,000,
 * which is what a long chat on Gemini used to get when Groq took over. Measured on Groq at a
 * 121-message conversation: 2,000 tokens of history is a 4,035-token prompt that answers, 8,000 is
 * refused outright.
 */
internal fun chatBudget(providers: Collection<AiProvider>): ChatBudget =
    // No configured provider means no request, and the smaller budget is the safe answer.
    if (providers.isNotEmpty() && providers.all { it.tokenBudgetPerMinute >= WIDE_MINUTE_TOKENS }) {
        ChatBudget(historyTokens = WIDE_HISTORY_TOKENS, digestLimit = WIDE_DIGEST_BOTTLES)
    } else {
        ChatBudget(historyTokens = THIN_HISTORY_TOKENS, digestLimit = THIN_DIGEST_BOTTLES)
    }

/** What one message costs the prompt, before the caller strips its image. */
internal fun tokensOf(message: ChatMessage): Int =
    message.text.length / CHARS_PER_TOKEN + MESSAGE_OVERHEAD

/**
 * The turns to send with the next chat request.
 *
 * This used to be the last twelve messages, which bounds the count rather than the size: twelve
 * long answers are still a request the fallback provider cannot afford, and the free tier that
 * matters here is Groq's 8,000 tokens a minute. The window now closes on a token budget, newest
 * first, so the previous answer — the thing the repetition rule reads — is the last thing kept.
 *
 * The newest message always goes, even when it alone is over budget: it is the question, and an
 * answer to a trimmed question is worse than a request the limiter has to pace.
 */
internal fun chatHistory(messages: List<ChatMessage>, budgetTokens: Int): List<ChatMessage> {
    if (messages.isEmpty()) return emptyList()
    val kept = ArrayDeque<ChatMessage>()
    var used = 0
    for (message in messages.asReversed()) {
        val cost = tokensOf(message)
        if (kept.isNotEmpty() && used + cost > budgetTokens) break
        used += cost
        kept.addFirst(message)
    }
    return kept.toList()
}
