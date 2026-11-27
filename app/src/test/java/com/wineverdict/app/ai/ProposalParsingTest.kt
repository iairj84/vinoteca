package com.wineverdict.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The one machine-readable line a chat answer may carry.
 *
 * This is prose on purpose — the chat is the request that wants the model to reason — so a proposal
 * arrives as a trailing marker rather than a whole JSON reply. What matters is that the marker never
 * reaches the screen, that a malformed one costs the user the proposal rather than leaving a stray
 * brace, that only one of the two kinds arrives at a time, and that a sentence which merely begins
 * with "Move" is not mistaken for one.
 */
class ProposalParsingTest {

    @Test
    fun theMarkerLineIsNotPartOfTheAnswer() {
        val reply = ProposalParsing.split(
            """
            The app will show you the Cabernet Sauvignons it found and move them once you confirm.

            MOVE {"grape":"Cabernet Sauvignon","location":"Bar Fridge"}
            """.trimIndent()
        )
        assertEquals(
            "The app will show you the Cabernet Sauvignons it found and move them once you confirm.",
            reply.text
        )
        assertEquals("Bar Fridge", reply.move?.location)
        assertEquals("Cabernet Sauvignon", reply.move?.grape)
        assertNull(reply.update)
    }

    @Test
    fun anAnswerWithNoMarkerIsKeptAsWritten() {
        val answer = "Two bottles are ready: the 2019 Seven Hills and the 2020 White Bluffs."
        val reply = ProposalParsing.split(answer)
        assertEquals(answer, reply.text)
        assertNull(reply.move)
        assertNull(reply.update)
    }

    @Test
    fun aSentenceBeginningWithMoveIsNotAMarker() {
        val reply = ProposalParsing.split("Move them to the Bar Fridge when you confirm.")
        assertEquals("Move them to the Bar Fridge when you confirm.", reply.text)
        assertNull(reply.move)
    }

    @Test
    fun aFencedMarkerParsesAndLeavesNoFenceBehind() {
        val reply = ProposalParsing.split(
            "The app will match them.\n```json\n" +
                "MOVE {\"grape\":\"Cabernet Sauvignon\",\"location\":\"Bar Fridge\"}\n```"
        )
        assertEquals("The app will match them.", reply.text)
        assertEquals("Bar Fridge", reply.move?.location)
    }

    @Test
    fun anUnreadableMarkerLeavesTheProseWithoutTheLine() {
        val reply = ProposalParsing.split("The app will match them.\nMOVE {\"grape\":\"Cabernet Sauvignon\"")
        assertEquals("The app will match them.", reply.text)
        assertNull(reply.move)
    }

    @Test
    fun aMarkerWithoutAPlaceIsRefused() {
        val reply = ProposalParsing.split("Here you go.\nMOVE {\"grape\":\"Cabernet Sauvignon\"}")
        assertEquals("Here you go.", reply.text)
        assertNull(reply.move)
    }

    @Test
    fun aGrapeListAndQuotedYearsAreRead() {
        val reply = ProposalParsing.split(
            "The app will match them.\nMOVE {\"grapes\":[\"Cabernet Sauvignon\"],\"vintageFrom\":\"2019\"," +
                "\"vintage_to\":2021,\"location\":\"bar fridge\"}"
        )
        assertEquals("Cabernet Sauvignon", reply.move?.grape)
        assertEquals(2019, reply.move?.vintageFrom)
        assertEquals(2021, reply.move?.vintageTo)
        assertEquals("bar fridge", reply.move?.location)
    }

    @Test
    fun theLastMarkerWinsAndNoMarkerSurvivesInTheText() {
        val reply = ProposalParsing.split(
            "The app will match them.\nMOVE {\"grape\":\"Merlot\",\"location\":\"Rack\"}\n" +
                "MOVE {\"grape\":\"Cabernet Sauvignon\",\"location\":\"Bar Fridge\"}"
        )
        assertEquals("Bar Fridge", reply.move?.location)
        assertEquals("Cabernet Sauvignon", reply.move?.grape)
        assertEquals("The app will match them.", reply.text)
    }

    @Test
    fun aLineBeginningWithMoveAndNoBraceIsLeftAlone() {
        val answer = "MOVE: move the bottles yourself.\n\nOr the app can."
        assertEquals(answer, ProposalParsing.split(answer).text)
    }

    @Test
    fun whereTheBottlesLiveNowIsRead() {
        val reply = ProposalParsing.split(
            "The app will match them.\n" +
                "MOVE {\"producer\":\"Prospice\",\"fromLocation\":\"unassigned\",\"location\":\"Garage Fridge\"}"
        )
        assertEquals("unassigned", reply.move?.fromLocation)
        assertEquals("Garage Fridge", reply.move?.location)
    }

    @Test
    fun fromIsReadAsWhereTheBottlesLiveNow() {
        val reply = ProposalParsing.split(
            "The app will match them.\n" +
                "MOVE {\"producer\":\"Prospice\",\"from\":\"Main rack\",\"to\":\"Garage Fridge\"}"
        )
        assertEquals("Main rack", reply.move?.fromLocation)
        assertEquals("Garage Fridge", reply.move?.location)
    }

    @Test
    fun anUpdateLineCarriesItsValues() {
        val reply = ProposalParsing.split(
            "The app will show you the bottles it matched and change them once you confirm.\n" +
                "UPDATE {\"producer\":\"Prospice\",\"slot\":\"Top shelf\",\"price\":48," +
                "\"note\":\"Dusty, drink by 2028\",\"quantity\":3,\"vintage\":2019}"
        )
        val update = reply.update
        assertEquals("Prospice", update?.producer)
        assertEquals("Top shelf", update?.slot)
        assertEquals(48.0, update?.price)
        assertEquals("Dusty, drink by 2028", update?.note)
        assertEquals(3, update?.quantity)
        assertEquals(2019, update?.vintage)
        assertNull(reply.move)
    }

    @Test
    fun aQuotedPriceAndAListOfGrapesAreRead() {
        val reply = ProposalParsing.split(
            "The app will match them.\n" +
                "UPDATE {\"grapes\":[\"Cabernet Sauvignon\"],\"price\":\"USD 48.00\",\"quantity\":\"3\"}"
        )
        assertEquals("Cabernet Sauvignon", reply.update?.grape)
        assertEquals(48.0, reply.update?.price)
        assertEquals(3, reply.update?.quantity)
    }

    @Test
    fun anEmptySlotIsPresentOnPurposeAndClearsIt() {
        val reply = ProposalParsing.split("The app will match them.\nUPDATE {\"producer\":\"Prospice\",\"slot\":\"\"}")
        assertEquals("", reply.update?.slot)
    }

    @Test
    fun aBlankNoteIsNoNoteAtAll() {
        val reply = ProposalParsing.split(
            "The app will match them.\nUPDATE {\"producer\":\"Prospice\",\"note\":\"  \"}"
        )
        assertNull(reply.update?.note)
        // And with nothing else asked for, there is no proposal to show.
        assertNull(reply.update)
    }

    @Test
    fun anUpdateLineWithNoValueIsNotAProposal() {
        val reply = ProposalParsing.split("The app will match them.\nUPDATE {\"producer\":\"Prospice\"}")
        assertEquals("The app will match them.", reply.text)
        assertNull(reply.update)
    }

    @Test
    fun theLastMarkerDecidesBetweenAMoveAndAChange() {
        val reply = ProposalParsing.split(
            "The app will match them.\nMOVE {\"producer\":\"Prospice\",\"location\":\"Bar Fridge\"}\n" +
                "UPDATE {\"producer\":\"Prospice\",\"price\":48}"
        )
        assertNull(reply.move)
        assertEquals(48.0, reply.update?.price)
        assertEquals("The app will match them.", reply.text)
    }
}
