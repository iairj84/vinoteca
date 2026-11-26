package com.wineverdict.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which provider each job puts in front.
 *
 * Reasoning over a whole wine list at 4,000 tokens is a different job from reading a label, and a
 * chat turn is a third, because every turn resends the conversation. One function decides the order
 * for all three, so "who answers first" has exactly one home.
 */
class AdviceModelTest {

    @Test
    fun aScanKeepsThePrimaryProviderFirst() {
        assertEquals(
            listOf(AiProvider.GEMINI, AiProvider.GROQ),
            AiProvider.order(AiJob.READ, listOf(AiProvider.GEMINI, AiProvider.GROQ))
        )
    }

    @Test
    fun groqLeadsTheAdviceCallEvenWhenGeminiIsPrimary() {
        assertEquals(
            listOf(AiProvider.GROQ, AiProvider.GEMINI),
            AiProvider.order(AiJob.ADVICE, listOf(AiProvider.GEMINI, AiProvider.GROQ))
        )
    }

    @Test
    fun geminiLeadsTheChatEvenWhenGroqIsPrimary() {
        // A chat turn resends the conversation; Groq's 8,000-token minute is two or three turns.
        assertEquals(
            listOf(AiProvider.GEMINI, AiProvider.GROQ),
            AiProvider.order(AiJob.CHAT, listOf(AiProvider.GROQ, AiProvider.GEMINI))
        )
    }

    @Test
    fun theChatAndTheAdviceCallDoNotShareALeadProvider() {
        val both = listOf(AiProvider.GROQ, AiProvider.GEMINI)
        assertEquals(AiProvider.GROQ, AiProvider.order(AiJob.ADVICE, both).first())
        assertEquals(AiProvider.GEMINI, AiProvider.order(AiJob.CHAT, both).first())
    }

    @Test
    fun theRestKeepTheirRelativeOrder() {
        // A user with three keys and OpenRouter primary still gets Gemini before OpenRouter on the
        // advice call: only the lead moves.
        assertEquals(
            listOf(AiProvider.GROQ, AiProvider.OPENROUTER, AiProvider.GEMINI),
            AiProvider.order(
                AiJob.ADVICE,
                listOf(AiProvider.OPENROUTER, AiProvider.GEMINI, AiProvider.GROQ)
            )
        )
    }

    @Test
    fun reorderingNeverAddsAProviderWithoutAKey() {
        // order() only reorders what the configured list returned; it cannot conjure a provider.
        assertEquals(listOf(AiProvider.GROQ), AiProvider.order(AiJob.CHAT, listOf(AiProvider.GROQ)))
        assertEquals(emptyList<AiProvider>(), AiProvider.order(AiJob.READ, emptyList()))
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
