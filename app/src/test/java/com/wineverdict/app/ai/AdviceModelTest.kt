package com.wineverdict.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The advice call and the chat do not go wherever the scan goes.
 *
 * Reasoning over a whole wine list at 4,000 tokens is a different job from reading a label, so it
 * has its own model field and its own provider order: Groq first when it has a key, then Gemini.
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
