package com.wineverdict.app.ai

import com.wineverdict.app.model.Wine
import com.wineverdict.app.model.WineType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Step 11: the list advice prompt, built from the photographed Spanish list.
 *
 * `ListAdviceTest` checks the contract on four hand-made labels. This one uses the real fixture the
 * live runs used — 26 printed lines under five section headings — because the properties that fail
 * quietly are the ones about the whole page: a line that loses its number, a section that stops
 * being carried, a brief that grows past what the request can carry.
 */
class ListAdviceFixtureTest {

    private val brief = """
        Smooth, dry reds with restrained oak; nothing sweet, nothing above 14% alcohol.
        Sparks fine for a celebration. Two glasses on a weekday, a bottle at the weekend with food.
        Loved: 2016 Barolo (tar and roses), 2019 Ultreia Mencía, 2019 Chivite Colección 125.
        Avoid: heavily oaked Chardonnay, anything off-dry, and wines that taste of the winery.
    """.trimIndent()

    private val fixture: List<ScannedLabel> = parseFixture()

    private val prompt = Prompts.listAdvice(
        brief = brief,
        cellarSummary = "2019 Ultreia Mencía · 2016 Barolo · 2020 Aalto",
        currency = "USD",
        bottles = fixture
    )

    /**
     * The fixture is the printed page: section headings, then `name, region year | glass | bottle`.
     * The years stay inside the name rather than being lifted into `vintage`, because what this test
     * is about is numbering, sections and prices, and the printed line is the identity the reader
     * checks against the photo.
     */
    private fun parseFixture(): List<ScannedLabel> {
        val text = javaClass.getResourceAsStream("/lists/spanish_list_26.txt")
            ?.bufferedReader()
            ?.readText()
            ?: error("fixture lists/spanish_list_26.txt is missing from the test resources")
        var section = WineType.RED
        return text.lines().mapNotNull { raw ->
            val line = raw.trim()
            when {
                line.isEmpty() -> null
                !line.contains('|') -> {
                    section = when (line) {
                        "SPARKLING" -> WineType.SPARKLING
                        "WHITE" -> WineType.WHITE
                        "ORANGE" -> WineType.ORANGE
                        "ROSADO" -> WineType.ROSE
                        "RED" -> WineType.RED
                        else -> error("unknown section in the fixture: $line")
                    }
                    null
                }
                else -> {
                    val parts = line.split('|').map { it.trim() }
                    val printed = parts[0]
                    val glass = parts[1].toDoubleOrNull()
                    val bottle = parts[2].toDoubleOrNull()
                    val grapes = Regex("\\(([^)]*)\\)").findAll(printed).lastOrNull()
                        ?.groupValues?.get(1)
                        ?.split(',')
                        ?.map { it.trim() }
                        ?.filter { it.isNotEmpty() }
                        .orEmpty()
                    ScannedLabel(
                        wine = Wine(
                            name = printed,
                            type = section,
                            grapes = grapes,
                            price = bottle,
                            currency = "USD"
                        ),
                        fitScore = null,
                        fitReason = "",
                        flavorTags = emptyList(),
                        priceEstimate = bottle,
                        confidence = "high",
                        readNotes = "",
                        glassPrice = glass
                    )
                }
            }
        }
    }

    @Test
    fun theFixtureIsTheWholePrintedPage() {
        // 26 lines in the file, not the 31 the earlier plan text assumed: the photo this came from
        // has 26 wines on it, and the prompt is checked against the photo.
        assertEquals(26, fixture.size)
        assertEquals(4, fixture.count { it.wine.type == WineType.SPARKLING })
        assertEquals(7, fixture.count { it.wine.type == WineType.WHITE })
        assertEquals(2, fixture.count { it.wine.type == WineType.ORANGE })
        assertEquals(3, fixture.count { it.wine.type == WineType.ROSE })
        assertEquals(10, fixture.count { it.wine.type == WineType.RED })
    }

    @Test
    fun everyWineIsNumberedAndCarriesItsSection() {
        val numbered = prompt.lines().map { it.trim() }.filter { Regex("^\\d+\\. ").containsMatchIn(it) }
        assertEquals("one line per wine", fixture.size, numbered.size)
        fixture.forEachIndexed { index, label ->
            val line = numbered[index]
            assertTrue("line ${index + 1} lost its number: $line", line.startsWith("${index + 1}. "))
            assertTrue("line ${index + 1} lost the wine: $line", line.contains(label.wine.title))
            // The section is the only thing that says a rosado is a rosé rather than a red, so it
            // has to survive into the line the model numbers its picks against.
            assertTrue(
                "line ${index + 1} lost its section: $line",
                line.contains(" — ${label.wine.type.label.lowercase()}")
            )
        }
    }

    @Test
    fun thePricesOnALineAreTheOnesPrinted() {
        val olabarri = prompt.lines().first { it.contains("Viña Olabarri") }
        assertTrue(olabarri, olabarri.contains("bottle USD 58"))
        assertTrue(olabarri, olabarri.contains("glass USD 15"))

        // A magnum with no glass price must not acquire one.
        val tondonia = prompt.lines().first { it.contains("Tondonia") }
        assertTrue(tondonia, tondonia.contains("bottle USD 380"))
        assertFalse(tondonia, tondonia.contains("glass USD"))
    }

    @Test
    fun theBriefRidesInWholeAndFitsTheRequest() {
        assertTrue("the fixture brief should be a short one", brief.length < 1_500)
        assertTrue(prompt.contains(brief))
        val tasteSection = prompt.substringAfter("MY TASTE").substringBefore("WHAT I ALREADY OWN")
        assertTrue(tasteSection.trim().length < 1_500)
    }

    @Test
    fun thePromptAsksForNothingBanned() {
        // The wording the redesign removed: an instruction that invited the model to answer from
        // memory instead of from the page. A prompt that grows it back is the regression.
        assertFalse(prompt.lowercase().contains("never from memory"))
        assertTrue(prompt.contains("WINES"))
        assertTrue(prompt.contains("PICK 1 | <line number> | "))
    }
}
