package com.wineverdict.app.ai

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The line reader's `VENUE:` line. A menu is titled by the place when the page prints one, and by
 * its date when it does not — so a blank answer has to stay blank rather than become "-".
 */
class VenueLineTest {

    @Test
    fun thePlaceIsTakenAsPrinted() {
        val raw = """
            SCENE: list
            VENUE: Osteria Francescana
            Producer | Cuvée | 2020 | Emília | Sangiovese | red | 54 | 14 | high | 1 | what | why
        """.trimIndent()

        assertEquals("Osteria Francescana", ScanParsing.venueLine(raw))
    }

    @Test
    fun aDashLineIsNoName() {
        // The prompt asks for "-" when the page names nowhere; that must not become the title.
        val raw = "SCENE: list\nVENUE: -\nProducer | Cuvée | 2020 | Emília | Sangiovese | red | 54 | 14"
        assertEquals("", ScanParsing.venueLine(raw))
    }

    @Test
    fun aReaderThatSkippedTheLineLeavesTheDate() {
        val raw = "SCENE: shelf\nProducer | Cuvée | 2020 | Emília | Sangiovese | red | 54 | 14"
        assertEquals("", ScanParsing.venueLine(raw))
        // A wine line mentioning the word must not be mistaken for the venue line.
        assertEquals("", ScanParsing.venueLine("Producer | Venue: the room | 2020"))
    }
}
