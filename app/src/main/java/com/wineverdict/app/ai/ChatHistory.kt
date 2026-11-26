package com.wineverdict.app.ai

import com.wineverdict.app.model.ChatMessage

/** Roughly four characters to a token, which is what the limiter already assumes. */
private const val CHARS_PER_TOKEN = 4

/** Role, separators and the rest of a chat turn's framing. */
private const val MESSAGE_OVERHEAD = 4

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
