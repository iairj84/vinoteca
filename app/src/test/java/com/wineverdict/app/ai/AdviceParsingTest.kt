package com.wineverdict.app.ai

import com.wineverdict.app.model.Wine
import com.wineverdict.app.model.WineType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Step 6: the advice answer, checked before anything is shown.
 *
 * The model writes about a page it cannot see, so a line that names a wine the page does not have,
 * a glass the menu does not pour, or a colour the section contradicts is dropped rather than shown
 * and explained. The 26-wine fixture is the page under test; line 16 is the Zestos rosado, which is
 * the case that started this — a rosé offered as "a red by the glass".
 */
class AdviceParsingTest {

    private fun fixture(): List<ScannedLabel> {
        val lines = checkNotNull(javaClass.getResourceAsStream("/lists/spanish_list_26.txt")) {
            "fixture missing from the test classpath"
        }.bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() }
        var type = WineType.SPARKLING
        val out = mutableListOf<ScannedLabel>()
        for (line in lines) {
            if (!line.contains(" | ")) {
                type = when (line.uppercase()) {
                    "SPARKLING" -> WineType.SPARKLING
                    "WHITE" -> WineType.WHITE
                    "ORANGE" -> WineType.ORANGE
                    "ROSADO" -> WineType.ROSE
                    else -> WineType.RED
                }
                continue
            }
            val parts = line.split("|").map { it.trim() }
            val head = parts[0].substringBefore(',').trim()
            out += ScannedLabel(
                wine = Wine(
                    producer = head,
                    name = "",
                    type = type,
                    price = parts.getOrNull(2)?.toDoubleOrNull(),
                    currency = "USD"
                ),
                fitScore = null,
                fitReason = "",
                flavorTags = emptyList(),
                priceEstimate = null,
                confidence = "medium",
                readNotes = "",
                glassPrice = parts.getOrNull(1)?.toDoubleOrNull()
            )
        }
        return out
    }

    private fun page() = SceneScan(Scene.LIST, fixture(), "")

    @Test
    fun theFixturePutsZestosUnderRosadoWithAGlassPrice() {
        val bottles = fixture()
        assertEquals(26, bottles.size)
        assertEquals(WineType.ROSE, bottles[15].wine.type)
        assertTrue(bottles[15].wine.producer.contains("Zestos"))
        assertEquals(15.0, bottles[15].glassPrice!!, 0.001)
    }

    @Test
    fun aCompleteAnswerIsParsed() {
        val raw = """
            PICK 1 | 22 | Ultreia, the Bierzo
            WHY: Mencía from slate. It is the wine on this page that tastes of somewhere.
            PICK 2 | 26 | Tondonia, if the money is there
            WHY: A Rioja with thirty years on it, at a price no other line here matches.
            GLASS | 21 | The Tintilla
            WHY: Served chilled, and the only glass that is not a generic red.
            SKIP | 11 | Albariño at ninety is not this evening.
            SKIP | 3 | The second Cava on the page, at the same price as the first.
            NOTE: The Sketch at 260 is the splurge worth knowing about.
        """.trimIndent()
        val advice = ScanParsing.parseAdvice(raw, page())
        assertNotNull(advice)
        assertEquals(listOf(21, 25), advice!!.picks.map { it.index })
        assertEquals("Ultreia, the Bierzo", advice.picks[0].headline)
        assertTrue(advice.picks[0].why.startsWith("Mencía from slate."))
        assertEquals(20, advice.glass!!.index)
        assertEquals(listOf(10, 2), advice.skips.map { it.index })
        assertTrue(advice.note.contains("Sketch"))
        assertTrue(advice.dropped.isEmpty())
    }

    @Test
    fun aWhyThatWrapsAcrossLinesIsJoined() {
        val raw = """
            PICK 1 | 22 | Ultreia
            WHY: Mencía from slate, grown high and picked late.
            It is the one line here that tastes of a place rather than a tank.
        """.trimIndent()
        val advice = ScanParsing.parseAdvice(raw, page())!!
        assertTrue(advice.picks[0].why.contains("tastes of a place rather than a tank"))
    }

    @Test
    fun aLineNumberThePageDoesNotHaveIsDropped() {
        val raw = "PICK 1 | 99 | an invented bottle\nWHY: nothing on this page\nPICK 2 | 22 | Ultreia"
        val advice = ScanParsing.parseAdvice(raw, page())!!
        assertEquals(listOf(21), advice.picks.map { it.index })
        assertTrue(advice.dropped.any { it.contains("no such line") })
    }

    @Test
    fun aGlassWithoutAGlassPriceIsDropped() {
        // Line 4 prints no glass price; line 21 does.
        val noGlass = "PICK 1 | 22 | Ultreia\nGLASS | 4 | the Dama by the glass"
        assertNull(ScanParsing.parseAdvice(noGlass, page())!!.glass)
        assertTrue(ScanParsing.parseAdvice(noGlass, page())!!.dropped.any { it.contains("no glass price") })

        val withGlass = "PICK 1 | 22 | Ultreia\nGLASS | 21 | the Tintilla"
        assertEquals(20, ScanParsing.parseAdvice(withGlass, page())!!.glass!!.index)
    }

    @Test
    fun theZestosRosadoCannotBePickedAsARed() {
        val raw = """
            PICK 1 | 16 | A red by the glass, and the cheapest thing on the page
            WHY: It drinks like a light red, which is what this diner wants.
            PICK 2 | 22 | Ultreia
            WHY: Mencía from slate.
        """.trimIndent()
        val advice = ScanParsing.parseAdvice(raw, page())!!
        assertEquals(listOf(21), advice.picks.map { it.index })
        assertTrue(
            advice.dropped.any { it.contains("described as red") && it.contains("rosé") }
        )
    }

    @Test
    fun theZestosRosadoCannotBeTheGlassAsARed() {
        val raw = "PICK 1 | 22 | Ultreia\nGLASS | 16 | a red by the glass"
        val advice = ScanParsing.parseAdvice(raw, page())!!
        assertNull(advice.glass)
        assertTrue(advice.dropped.any { it.contains("GLASS 16") })
    }

    @Test
    fun redFruitIsATastingNoteNotAColourClaim() {
        val raw = """
            PICK 1 | 16 | The Zestos rosado
            WHY: Pale, with red fruit and a bitter finish — and it is the only rosado poured by the glass.
        """.trimIndent()
        val advice = ScanParsing.parseAdvice(raw, page())!!
        assertEquals(listOf(15), advice.picks.map { it.index })
        assertTrue(advice.dropped.isEmpty())
    }

    @Test
    fun aBottleIsPickedOnce() {
        val raw = "PICK 1 | 22 | Ultreia\nPICK 2 | 22 | Ultreia again\nPICK 3 | 26 | Tondonia"
        val advice = ScanParsing.parseAdvice(raw, page())!!
        assertEquals(listOf(21, 25), advice.picks.map { it.index })
        assertTrue(advice.dropped.any { it.contains("already picked") })
    }

    @Test
    fun moreThanThreePicksAreCapped() {
        val raw = "PICK 1 | 22 | a\nPICK 2 | 26 | b\nPICK 3 | 21 | c\nPICK 4 | 19 | d"
        val advice = ScanParsing.parseAdvice(raw, page())!!
        assertEquals(3, advice.picks.size)
        assertTrue(advice.dropped.any { it.contains("more than three") })
    }

    @Test
    fun noValidPickMeansTheCallerFallsBack() {
        assertNull(ScanParsing.parseAdvice("", page()))
        assertNull(ScanParsing.parseAdvice("NOTE: nothing to say", page()))
        assertNull(ScanParsing.parseAdvice("SKIP | 22 | not for tonight", page()))
        assertNull(ScanParsing.parseAdvice("PICK 1 | 99 | invented", page()))
        assertNull(ScanParsing.parseAdvice("PICK 1 | 16 | a red, the only chilled one", page()))
    }

    @Test
    fun caseBulletsAndStrayWhitespaceAreForgiven() {
        val raw = """
              - pick 1 | 22 | ultreia
              why: mencia from slate
        """.trimIndent()
        val advice = ScanParsing.parseAdvice(raw, page())!!
        assertEquals("ultreia", advice.picks[0].headline)
        assertEquals("mencia from slate", advice.picks[0].why)
    }

    @Test
    fun theValidatedPicksBecomeTheRanks() {
        val raw = "PICK 1 | 26 | Tondonia\nPICK 2 | 22 | Ultreia"
        val ranked = ScanParsing.applyAdvice(page(), raw)!!
        assertEquals(1, ranked.bottles[25].listRank)
        assertEquals(2, ranked.bottles[21].listRank)
        assertNull(ranked.bottles[15].listRank)
    }
}
