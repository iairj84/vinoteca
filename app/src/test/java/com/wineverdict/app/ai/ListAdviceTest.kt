package com.wineverdict.app.ai

import com.wineverdict.app.model.Wine
import com.wineverdict.app.model.WineType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Step 5: ask for a few explained picks instead of a rank for every line.
 *
 * The prompt is checked for the things the answer depends on — numbered lines carrying the type,
 * the place, the grapes and both prices, and the exact reply tags — and the stop-gap reader is
 * checked for placing picks in order and refusing line numbers that do not exist.
 */
class ListAdviceTest {

    private fun label(
        producer: String,
        name: String,
        type: WineType,
        region: String,
        grapes: List<String> = emptyList(),
        bottle: Double? = null,
        glass: Double? = null
    ) = ScannedLabel(
        wine = Wine(
            producer = producer,
            name = name,
            vintage = 2022,
            type = type,
            region = region,
            grapes = grapes,
            price = bottle,
            currency = "USD"
        ),
        fitScore = null,
        fitReason = "",
        flavorTags = emptyList(),
        priceEstimate = bottle,
        confidence = "medium",
        readNotes = "",
        glassPrice = glass
    )

    private val page = listOf(
        label("Miquel Pons", "Cava Brut", WineType.SPARKLING, "Catalonia", listOf("Macabeo"), 50.0, 13.0),
        label("Zestos", "Vinos de Madrid", WineType.ROSE, "Vinos de Madrid", listOf("Garnacha"), 58.0, 15.0),
        label("Raúl Pérez", "Ultreia", WineType.RED, "Bierzo", listOf("Mencía"), 62.0, null),
        label("Vall Llach", "Embruix", WineType.RED, "Priorat", listOf("Garnacha", "Cariñena"), 68.0, null)
    )

    private fun prompt() = Prompts.listAdvice(
        brief = "I mostly drink red. I prefer restrained oak.",
        cellarSummary = "2019 Ultreia",
        currency = "USD",
        bottles = page
    )

    @Test
    fun theWinesAreNumberedWithTypePlaceGrapesAndBothPrices() {
        val text = prompt()
        assertTrue(text.contains("1. 2022 Cava Brut · Miquel Pons — sparkling — Catalonia (Macabeo) — bottle USD 50, glass USD 13"))
        assertTrue(text.contains("2. 2022 Vinos de Madrid · Zestos — rosé — Vinos de Madrid (Garnacha) — bottle USD 58, glass USD 15"))
        // No glass price printed: the line simply has none.
        assertTrue(text.contains("3. 2022 Ultreia · Raúl Pérez — red — Bierzo (Mencía) — bottle USD 62"))
    }

    @Test
    fun theReplyFormatIsTheOneThatWillBeParsed() {
        val text = prompt()
        assertTrue(text.contains("PICK 1 | <line number> | "))
        assertTrue(text.contains("WHY:"))
        assertTrue(text.contains("SKIP | <line number> | "))
        assertTrue(text.contains("GLASS | <line number> | "))
        assertTrue(text.contains("NOTE:"))
    }

    @Test
    fun theTasteAndTheCellarAreCarriedIn() {
        val text = prompt()
        assertTrue(text.contains("I mostly drink red."))
        assertTrue(text.contains("2019 Ultreia"))
    }

    @Test
    fun anEmptyBriefAndCellarStillProduceAUsablePrompt() {
        val text = Prompts.listAdvice("", "", "USD", page)
        assertTrue(text.contains("(I have not told you what I like"))
        assertTrue(text.contains("(nothing yet)"))
        assertTrue(text.contains("(none given)"))
    }

    @Test
    fun picksArePlacedInTheOrderTheModelGave() {
        val raw = """
            PICK 1 | 3 | The Bierzo
            WHY: Mencía, cool and Atlantic.
            PICK 2 | 4 | The Priorat
            WHY: Old vines, warm slate.
            SKIP | 1 | Chilled Cava is the easy order, not the right one.
        """.trimIndent()
        val read = SceneScan(Scene.LIST, page, "")
        val advised = ScanParsing.applyAdvice(read, raw)
        assertEquals(1, advised!!.bottles[2].listRank)
        assertEquals(2, advised.bottles[3].listRank)
        assertNull(advised.bottles[0].listRank)
    }

    @Test
    fun aLineNumberThatIsNotOnThePageIsIgnored() {
        val raw = "PICK 1 | 9 | invented\nWHY: nothing\nPICK 2 | 2 | the rosado\nWHY: dry and pale"
        val advised = ScanParsing.applyAdvice(SceneScan(Scene.LIST, page, ""), raw)
        assertEquals(1, advised!!.bottles[1].listRank)
        assertTrue(advised.bottles.none { it.listRank == 2 })
    }

    @Test
    fun aRepeatedLineNumberIsPlacedOnce() {
        val raw = "PICK 1 | 3 | first\nPICK 2 | 3 | again\nPICK 3 | 4 | second"
        val advised = ScanParsing.applyAdvice(SceneScan(Scene.LIST, page, ""), raw)!!
        assertEquals(1, advised.bottles[2].listRank)
        assertEquals(2, advised.bottles[3].listRank)
    }

    @Test
    fun nothingUsableMeansTheFallbackRuns() {
        assertNull(ScanParsing.applyAdvice(SceneScan(Scene.LIST, page, ""), ""))
        assertNull(ScanParsing.applyAdvice(SceneScan(Scene.LIST, page, ""), "NOTE: nothing to say"))
        assertNull(ScanParsing.applyAdvice(SceneScan(Scene.LIST, page, ""), "PICK 1 | 42 | invented"))
    }

    @Test
    fun aSkipIsNotAPick() {
        val raw = "SKIP | 1 | too sweet"
        assertNull(ScanParsing.applyAdvice(SceneScan(Scene.LIST, page, ""), raw))
    }

    /**
     * The live answer to the 26-line list carried one SKIP row naming three lines. The parser read
     * that field as the number 112526, found no such line, and dropped three skips that were all on
     * the page — the panel said so: "SKIP 11, 25, 26 — no such line on the page".
     */
    @Test
    fun aSkipRowThatNamesSeveralLinesSkipsEachOfThem() {
        val longPage = (1..26).map {
            label("Producer $it", "Wine $it", WineType.RED, "Rioja", bottle = 50.0)
        }
        val raw = """
            PICK 1 | 2 | the second
            WHY: it is the one.
            SKIP | 11, 25, 26 | Tempting, but not what the list is for.
        """.trimIndent()
        val advice = ScanParsing.parseAdvice(raw, SceneScan(Scene.LIST, longPage, ""))!!
        assertEquals(listOf(10, 24, 25), advice.skips.map { it.index })
        assertTrue(advice.dropped.isEmpty())
    }

    @Test
    fun aNumberThatReallyIsOffThePageCostsOnlyItself() {
        val shortPage = (1..4).map {
            label("Producer $it", "Wine $it", WineType.RED, "Rioja", bottle = 50.0)
        }
        val raw = "PICK 1 | 2 | the second\nWHY: it is the one.\nSKIP | 3, 27 | Tempting, but not for me."
        val advice = ScanParsing.parseAdvice(raw, SceneScan(Scene.LIST, shortPage, ""))!!
        assertEquals(listOf(2), advice.skips.map { it.index })
        assertTrue(advice.dropped.any { it.contains("SKIP 27") })
    }
}
