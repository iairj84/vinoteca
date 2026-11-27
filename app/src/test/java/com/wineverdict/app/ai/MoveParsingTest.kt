package com.wineverdict.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The one machine-readable line a chat answer may carry.
 *
 * This is prose on purpose — the chat is the request that wants the model to reason — so the move
 * arrives as a trailing marker rather than a whole JSON reply. What matters is that the marker never
 * reaches the screen, that a malformed one costs the user the move rather than a stray brace, and that
 * a sentence which merely begins with "Move" is not mistaken for one.
 */
class MoveParsingTest {

    @Test
    fun theMarkerLineIsNotPartOfTheAnswer() {
        val reply = MoveParsing.split(
            """
            The app will show you the Cabernet Sauvignons it found and move them once you confirm.

            MOVE {"grape":"Cabernet Sauvignon","location":"Bar Fridge"}
            """.trimIndent()
        )
        assertEquals(
            "The app will show you the Cabernet Sauvignons it found and move them once you confirm.",
            reply.text
        )
        assertEquals("Bar Fridge", reply.request?.location)
        assertEquals("Cabernet Sauvignon", reply.request?.grape)
    }

    @Test
    fun anAnswerWithNoMarkerIsKeptAsWritten() {
        val answer = "Two bottles are ready: the 2019 Seven Hills and the 2020 White Bluffs."
        val reply = MoveParsing.split(answer)
        assertEquals(answer, reply.text)
        assertNull(reply.request)
    }

    @Test
    fun aSentenceBeginningWithMoveIsNotAMarker() {
        val reply = MoveParsing.split("Move them to the Bar Fridge when you confirm.")
        assertEquals("Move them to the Bar Fridge when you confirm.", reply.text)
        assertNull(reply.request)
    }

    @Test
    fun aFencedMarkerParsesAndLeavesNoFenceBehind() {
        val reply = MoveParsing.split(
            "The app will match them.\n```json\n" +
                "MOVE {\"grape\":\"Cabernet Sauvignon\",\"location\":\"Bar Fridge\"}\n```"
        )
        assertEquals("The app will match them.", reply.text)
        assertEquals("Bar Fridge", reply.request?.location)
    }

    @Test
    fun anUnreadableMarkerLeavesTheProseWithoutTheLine() {
        val reply = MoveParsing.split("The app will match them.\nMOVE {\"grape\":\"Cabernet Sauvignon\"")
        assertEquals("The app will match them.", reply.text)
        assertNull(reply.request)
    }

    @Test
    fun aMarkerWithoutAPlaceIsRefused() {
        val reply = MoveParsing.split("Here you go.\nMOVE {\"grape\":\"Cabernet Sauvignon\"}")
        assertEquals("Here you go.", reply.text)
        assertNull(reply.request)
    }

    @Test
    fun aGrapeListAndQuotedYearsAreRead() {
        val reply = MoveParsing.split(
            "The app will match them.\nMOVE {\"grapes\":[\"Cabernet Sauvignon\"],\"vintageFrom\":\"2019\"," +
                "\"vintage_to\":2021,\"location\":\"bar fridge\"}"
        )
        assertEquals("Cabernet Sauvignon", reply.request?.grape)
        assertEquals(2019, reply.request?.vintageFrom)
        assertEquals(2021, reply.request?.vintageTo)
        assertEquals("bar fridge", reply.request?.location)
    }

    @Test
    fun theLastMarkerWinsAndNoMarkerSurvivesInTheText() {
        val reply = MoveParsing.split(
            "The app will match them.\nMOVE {\"grape\":\"Merlot\",\"location\":\"Rack\"}\n" +
                "MOVE {\"grape\":\"Cabernet Sauvignon\",\"location\":\"Bar Fridge\"}"
        )
        assertEquals("Bar Fridge", reply.request?.location)
        assertEquals("Cabernet Sauvignon", reply.request?.grape)
        assertEquals("The app will match them.", reply.text)
    }

    @Test
    fun aLineBeginningWithMoveAndNoBraceIsLeftAlone() {
        val answer = "MOVE: move the bottles yourself.\n\nOr the app can."
        assertEquals(answer, MoveParsing.split(answer).text)
    }
}
