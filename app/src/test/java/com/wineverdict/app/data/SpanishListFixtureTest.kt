package com.wineverdict.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 26-wine Spanish list every redesign step is tested against.
 *
 * It is a fixture, not a claim about a real menu: only the producer/region/grape text and the
 * section order come from the Nov 25 list; the prices are the ticket's sample values.
 *
 * The file is named 26 because that is how many wines it holds. The plan called it 31 and
 * referenced "line 16 = Zestos", which is only true in 26-line numbering — this test pins both
 * facts so a later step cannot quietly renumber the page.
 */
class SpanishListFixtureTest {

    private fun lines(): List<String> =
        checkNotNull(javaClass.getResourceAsStream("/lists/spanish_list_26.txt")) {
            "fixture missing from the test classpath"
        }.bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() }

    private fun wines(): List<String> =
        lines().filter { it.contains(" | ") }

    @Test
    fun holdsTwentySixWines() {
        assertEquals(26, wines().size)
    }

    @Test
    fun sectionsAndTheirCountsMatchTheMenu() {
        val sections = mutableListOf<String>()
        val counts = linkedMapOf<String, Int>()
        for (line in lines()) {
            if (line.contains(" | ")) {
                counts[sections.last()] = (counts[sections.last()] ?: 0) + 1
            } else {
                sections += line
                counts[line] = 0
            }
        }
        assertEquals(listOf("SPARKLING", "WHITE", "ORANGE", "ROSADO", "RED"), sections)
        assertEquals(listOf(4, 7, 2, 3, 10), sections.map { counts[it] })
    }

    @Test
    fun lineSixteenIsTheZestosRosado() {
        val sixteenth = wines()[15]
        assertTrue(sixteenth, sixteenth.contains("Zestos"))
        assertTrue(sixteenth, sixteenth.contains("Vinos de Madrid"))
    }

    @Test
    fun pricesAreGlassThenBottle() {
        // "Miquel Pons ... | 13 | 50" — glass first, bottle second, "-" when not printed.
        val first = wines().first()
        val parts = first.split("|").map { it.trim() }
        assertEquals("13", parts[1])
        assertEquals("50", parts[2])
        assertTrue(wines().any { it.split("|").map { p -> p.trim() }[1] == "-" })
    }
}
