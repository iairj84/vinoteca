package com.wineverdict.app.ai

import com.wineverdict.app.model.ChatMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one retry after a 413.
 *
 * Groq refuses a request larger than its 8,000-token minute with a 413 — measured, not assumed. The
 * chat sizes itself for the thinnest provider now, but a conversation can still outgrow the body
 * (a wide-only install that later adds a key, a digest that grew), and failing the answer when the
 * question alone would fit is the wrong outcome.
 */
class LeanPayloadTest {

    private fun target(provider: AiProvider) = Target(
        provider = provider,
        baseUrl = provider.defaultBaseUrl,
        key = "not-a-real-key",
        model = provider.defaultAdviceModels.first()
    )

    private fun body(
        turns: List<ChatMessage>,
        images: List<String> = emptyList()
    ) = Json.parseToJsonElement(
        leanPayload(target(AiProvider.GROQ), "system", turns, images, 0.5, 1_600)
    ).jsonObject

    private val conversation = listOf(
        ChatMessage(id = "1", role = "user", text = "a long question about Rioja"),
        ChatMessage(id = "2", role = "assistant", text = "a long answer"),
        ChatMessage(id = "3", role = "user", text = "the question I just asked")
    )

    @Test
    fun onlyTheNewestTurnSurvives() {
        val messages = body(conversation)["messages"]!!.jsonArray
        assertEquals(2, messages.size)
        assertEquals("system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals(
            "the question I just asked",
            messages[1].jsonObject["content"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun theQuestionKeepsOnePhoto() {
        val messages = body(conversation, images = listOf("first", "second"))["messages"]!!.jsonArray
        val parts = messages[1].jsonObject["content"]!!.jsonArray
        assertEquals(2, parts.size)
        assertTrue(parts[1].toString().contains("data:image/jpeg;base64,first"))
    }

    @Test
    fun theLeanBodyAsksForNoReasoningAndNoSchema() {
        val payload = body(conversation)
        assertNull(payload["reasoning_effort"])
        assertNull(payload["response_format"])
        // No reserve either: the budget is the answer's own, as it was before the thinking fix.
        assertEquals(1_600, payload["max_tokens"]!!.jsonPrimitive.int)
    }
}
