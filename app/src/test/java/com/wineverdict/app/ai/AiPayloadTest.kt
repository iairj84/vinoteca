package com.wineverdict.app.ai

import com.wineverdict.app.model.ChatMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The exact request body, pinned.
 *
 * Two fields caused the list judgement and the fine-print read to come back empty on Groq, and
 * neither is visible from the UI: `reasoning_effort` was `"none"`, which the API rejects with a 400,
 * and `max_tokens` was the answer's budget with no room for the reasoning that is billed against
 * the same number. Both are asserted here so a later refactor cannot quietly restore them.
 */
class AiPayloadTest {

    private fun target(provider: AiProvider) = Target(
        provider = provider,
        baseUrl = provider.defaultBaseUrl,
        key = "not-a-real-key",
        model = provider.defaultTextModels.first()
    )

    private fun payload(
        provider: AiProvider,
        thinking: Thinking?,
        maxTokens: Int = 800,
        jsonMode: Boolean = false
    ): JsonObject = Json.parseToJsonElement(
        chatPayload(
            target = target(provider),
            system = "system prompt",
            turns = listOf(ChatMessage(role = "user", text = "rank these")),
            images = emptyList(),
            jsonMode = jsonMode,
            thinking = thinking,
            temperature = 0.3,
            maxTokens = maxTokens
        )
    ).jsonObject

    @Test
    fun groqOffAsksForLowNotNone() {
        val body = payload(AiProvider.GROQ, Thinking.OFF)
        assertEquals("low", body["reasoning_effort"]!!.jsonPrimitive.content)
        assertFalse(body.toString().contains("\"none\""))
    }

    @Test
    fun everyProviderAndLevelAvoidsTheRejectedValue() {
        for (provider in AiProvider.entries) {
            for (level in Thinking.entries) {
                val value = reasoningValue(provider, level)
                assertTrue("$provider/$level", value == null || value in setOf("low", "medium", "high"))
            }
        }
    }

    @Test
    fun groqOffLeavesRoomForReasoning() {
        // 800 asked, 600 reserved: the fine-print read at 400 was the one that came back empty.
        assertEquals(1_400, payload(AiProvider.GROQ, Thinking.OFF, maxTokens = 800)["max_tokens"]!!.jsonPrimitive.int)
        assertEquals(1_000, payload(AiProvider.GROQ, Thinking.OFF, maxTokens = 400)["max_tokens"]!!.jsonPrimitive.int)
    }

    @Test
    fun mediumReservesMoreThanLow() {
        val off = payload(AiProvider.GROQ, Thinking.OFF, 800)["max_tokens"]!!.jsonPrimitive.int
        val low = payload(AiProvider.GROQ, Thinking.LOW, 800)["max_tokens"]!!.jsonPrimitive.int
        val medium = payload(AiProvider.GROQ, Thinking.MEDIUM, 800)["max_tokens"]!!.jsonPrimitive.int
        assertTrue(off < low)
        assertTrue(low < medium)
        assertEquals(2_600, medium)
    }

    @Test
    fun theBudgetHasACeiling() {
        assertEquals(8_000, outputBudget(7_000, Thinking.MEDIUM))
        assertEquals(800, outputBudget(800, null))
    }

    @Test
    fun geminiOffIsLowToo() {
        assertEquals("low", payload(AiProvider.GEMINI, Thinking.OFF)["reasoning_effort"]!!.jsonPrimitive.content)
    }

    @Test
    fun openRouterSendsNoReasoningField() {
        val body = payload(AiProvider.OPENROUTER, Thinking.OFF)
        assertNull(body["reasoning_effort"])
    }

    @Test
    fun jsonModeStillAsksForAnObject() {
        val body = payload(AiProvider.GROQ, Thinking.OFF, jsonMode = true)
        assertEquals(
            "json_object",
            body["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun systemAndTurnAreTheMessages() {
        val body = payload(AiProvider.GROQ, Thinking.OFF)
        val messages = body["messages"]!!.toString()
        assertTrue(messages.contains("system prompt"))
        assertTrue(messages.contains("rank these"))
        assertEquals("openai/gpt-oss-120b", body["model"]!!.jsonPrimitive.content)
    }
}
