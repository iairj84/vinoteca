package com.wineverdict.app.ai

import com.wineverdict.app.model.Wine
import com.wineverdict.app.model.WineType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Step 9: a shop shelf's advice answer, and the VALUE line it is the only job to carry.
 *
 * The shelf question is the list question with the money taken literally: on a menu a price is a
 * markup, so the parser ignores VALUE there; in a shop the printed price is the decision, and the
 * answer has to say whether it is a bargain. What a value claim may not do is attach itself to a
 * bottle whose price nobody read — that is the model inventing the number it compared.
 */
class ShelfAdviceTest {

    private fun label(producer: String, price: Double?): ScannedLabel = ScannedLabel(
        wine = Wine(
            producer = producer,
            name = "Reserva",
            type = WineType.RED,
            price = price,
            currency = "USD"
        ),
        fitScore = null,
        fitReason = "",
        flavorTags = emptyList(),
        priceEstimate = price,
        confidence = "medium",
        readNotes = "",
        glassPrice = null
    )

    private fun shelf(): SceneScan = SceneScan(
        Scene.SHELF,
        listOf(label("Olabarri", 58.0), label("Pecina", 70.0), label("Tondonia", null)),
        ""
    )

    @Test
    fun theValueWordAndItsClauseLandOnThePick() {
        val raw = """
            PICK 1 | 1 | Olabarri Reserva
            WHY: A Rioja with the tannin already softened.
            VALUE: good — a fair price for a Reserva with this much age.
            PICK 2 | 2 | Pecina Crianza
            WHY: Crianza fruit with a savoury edge.
            VALUE: poor — the same wine is cheaper elsewhere.
        """.trimIndent()
        val advice = ScanParsing.parseAdvice(raw, shelf())!!
        assertEquals("good", advice.picks[0].value)
        assertTrue(advice.picks[0].valueNote.contains("fair price"))
        assertEquals("poor", advice.picks[1].value)
        assertTrue(advice.picks[1].valueNote.startsWith("the same wine"))
    }

    @Test
    fun theValueClauseNeverBecomesPartOfTheWhy() {
        val raw = """
            PICK 1 | 1 | Olabarri Reserva
            WHY: A Rioja with the tannin already softened.
            VALUE: good — a fair price for a Reserva with this much age.
        """.trimIndent()
        val why = ScanParsing.parseAdvice(raw, shelf())!!.picks[0].why
        assertFalse(why.contains("VALUE"))
        assertFalse(why.contains("fair price"))
    }

    @Test
    fun aValueClaimAboutAPriceNobodyPrintedIsDropped() {
        val raw = "PICK 1 | 3 | Tondonia\nWHY: Thirty years old.\nVALUE: good — a bargain at this price."
        val pick = ScanParsing.parseAdvice(raw, shelf())!!.picks[0]
        assertNull(pick.value)
        assertEquals("", pick.valueNote)
    }

    @Test
    fun aMenuAnswerCarriesNoValueEvenWhenTheModelVolunteersOne() {
        val menu = SceneScan(Scene.LIST, shelf().bottles, "")
        val raw = "PICK 1 | 1 | Olabarri\nWHY: Soft tannin.\nVALUE: good — cheap for a Rioja."
        assertNull(ScanParsing.parseAdvice(raw, menu)!!.picks[0].value)
    }

    @Test
    fun thePromptNumbersEveryBottleAndAsksForAValue() {
        val prompt = Prompts.shelfAdvice("", "", "USD", shelf().bottles)
        assertTrue(prompt.contains("1. "))
        assertTrue(prompt.contains("Olabarri"))
        assertTrue(prompt.contains("3. "))
        assertTrue(prompt.contains("Tondonia"))
        assertTrue(prompt.contains("VALUE: good|fair|poor"))
        assertTrue(prompt.contains("Three picks at most"))
        // A shop sells bottles: a glass line would be answered with a price nobody printed.
        assertFalse(prompt.contains("GLASS |"))
    }
}
