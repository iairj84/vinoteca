package com.wineverdict.app.ai

import com.wineverdict.app.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The window a chat request carries.
 *
 * The stored transcript is capped at 60 messages by the chat repository, and the request used to cap
 * itself at the last 12 — a count, not a size. These pin the size: what goes is the oldest turn,
 * what stays is the previous answer, and the question is never trimmed.
 */
class ChatHistoryTest {

    private fun message(role: String, chars: Int, id: String) = ChatMessage(
        id = id,
        role = role,
        text = "x".repeat(chars)
    )

    @Test
    fun anEmptyConversationStaysEmpty() {
        assertEquals(emptyList<ChatMessage>(), chatHistory(emptyList(), budgetTokens = 2_000))
    }

    @Test
    fun theOldestTurnGoesFirst() {
        // 400 chars ≈ 104 tokens each, plus 4 of framing: about 108.
        val turns = (1..5).map { message(if (it % 2 == 0) "assistant" else "user", 400, "$it") }
        val kept = chatHistory(turns, budgetTokens = 220)
        assertEquals(listOf("4", "5"), kept.map { it.id })
    }

    @Test
    fun thePreviousAnswerIsKept() {
        val turns = listOf(
            message("user", 400, "1"),
            message("assistant", 400, "2"),
            message("user", 400, "3")
        )
        val kept = chatHistory(turns, budgetTokens = 220)
        assertEquals(listOf("2", "3"), kept.map { it.id })
    }

    @Test
    fun theQuestionIsNeverTrimmed() {
        val huge = message("user", 40_000, "2")
        val kept = chatHistory(listOf(message("assistant", 400, "1"), huge), budgetTokens = 500)
        assertEquals(listOf("2"), kept.map { it.id })
        assertEquals(40_000, kept.single().text.length)
    }

    @Test
    fun aGenerousBudgetKeepsEverything() {
        val turns = (1..12).map { message("user", 100, "$it") }
        assertEquals(12, chatHistory(turns, budgetTokens = 8_000).size)
    }

    @Test
    fun theWindowIsClosedBySizeNotByCount() {
        // Short turns fit where long ones would not, so a wide budget is not silently cut back to
        // a fixed number of messages.
        val short = (1..40).map { message("user", 40, "$it") }
        assertTrue(chatHistory(short, budgetTokens = 8_000).size > 12)
    }
}
