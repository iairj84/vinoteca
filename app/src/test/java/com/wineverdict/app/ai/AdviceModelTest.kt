package com.wineverdict.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The advice call and the chat do not go wherever the scan goes.
 *
 * Reasoning over a whole wine list at 4,000 tokens is a different job from reading a label, so it
 * has its own model field and its own provider order. The advice call and the chat do not share
 * that order: one is a single large reasoning step, the other resends the conversation every turn.
 */
class AdviceModelTest {

    @Test
    fun groqLeadsEvenWhenGeminiIsPrimary() {
        assertEquals(
            listOf(AiProvider.GROQ, AiProvider.GEMINI),
            AiProvider.adviceOrder(listOf(AiProvider.GEMINI, AiProvider.GROQ))
        )
    }

    @Test
    fun geminiLeadsTheChatEvenWhenGroqCouldAnswer() {
        // A chat turn resends the conversation; Groq's 8,000-token minute is two or three turns.
        assertEquals(
            listOf(AiProvider.GEMINI, AiProvider.GROQ),
            AiProvider.chatOrder(listOf(AiProvider.GROQ, AiProvider.GEMINI))
        )
    }

    @Test
    fun theChatAndTheAdviceCallDoNotShareAnOrder() {
        val both = listOf(AiProvider.GROQ, AiProvider.GEMINI)
        assertEquals(AiProvider.GROQ, AiProvider.adviceOrder(both).first())
        assertEquals(AiProvider.GEMINI, AiProvider.chatOrder(both).first())
    }

    @Test
    fun geminiAnswersWhenGroqHasNoKey() {
        assertEquals(
            listOf(AiProvider.GEMINI, AiProvider.OPENROUTER),
            AiProvider.adviceOrder(listOf(AiProvider.OPENROUTER, AiProvider.GEMINI))
        )
    }

    @Test
    fun everyProviderHasAnAdviceModel() {
        AiProvider.entries.forEach {
            assertTrue("${it.id} has no advice model", it.defaultAdviceModels.first().isNotBlank())
        }
    }

    @Test
    fun groqAdviceUsesTheModelThatWasMeasured() {
        // The free-tier measurement behind Step 0 was on this id; a change here needs a new one.
        assertEquals("openai/gpt-oss-120b", AiProvider.GROQ.defaultAdviceModels.first())
    }
}
