package com.wineverdict.app.ai

import com.wineverdict.app.model.WineType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The candidates a search names: grounded in the results the app fetched, named so the cellar can
 * match them again, and refused rather than invented when the answer holds no nameable wine.
 */
class BottleSearchTest {

    private val fetched = listOf(
        "https://www.stollerwine.com/wines/2025-rose",
        "https://www.wine-searcher.com/find/stoller+rose"
    )

    private fun parse(json: String) = BottleSearch.parse(json.trimIndent(), fetched)

    @Test
    fun aBottlingArrivesWithItsOwnColourAndVintage() {
        val found = parse(
            """
            {"bottles":[
              {"producer":"Stoller Family Estate","name":"Rosé of Pinot Noir","vintage":"2025",
               "type":"Rosé","country":"USA","region":"Dundee Hills","appellation":"Dundee Hills AVA",
               "grapes":"Pinot Noir","abv":"12.5","source":"https://www.stollerwine.com/wines/2025-rose",
               "caveat":""}
            ]}
            """
        )
        val wine = found.single()
        assertEquals("Stoller Family Estate", wine.producer)
        assertEquals("Rosé of Pinot Noir", wine.name)
        assertEquals(2025, wine.vintage)
        assertEquals(WineType.ROSE, wine.type)
        assertEquals("Dundee Hills", wine.region)
        assertEquals(listOf("Pinot Noir"), wine.grapes)
        assertEquals(12.5, wine.alcohol!!, 0.001)
        assertEquals("stollerwine.com", wine.origin)
        assertEquals("2025 Rosé of Pinot Noir · Stoller Family Estate", wine.title)
    }

    @Test
    fun aCandidateBecomesABottleThatKeepsItsColour() {
        val found = parse(
            """
            {"bottles":[{"producer":"Stoller","name":"Rosé","vintage":"2025","type":"rosé"}]}
            """
        )
        val bottle = found.single().toWine("USD")
        assertEquals(WineType.ROSE, bottle.type)
        assertEquals(1, bottle.quantity)
        assertEquals("search", bottle.source)
        assertEquals(2025, bottle.vintage)
    }

    @Test
    fun aSourceTheAppDidNotFetchIsNotASource() {
        val found = parse(
            """
            {"bottles":[{"producer":"Ridge","name":"Geyserville","vintage":"2019",
             "source":"https://invented.example.com/ridge"}]}
            """
        )
        assertEquals("", found.single().sourceUrl)
        assertEquals("", found.single().origin)
    }

    @Test
    fun anUnnamedEntryIsNotACandidate() {
        val found = parse(
            """
            {"bottles":[
              {"producer":"Stoller","name":"","vintage":"2025"},
              {"producer":"","name":"Rosé of Pinot Noir"},
              {"producer":"Ridge","name":"Unknown"},
              {"producer":"Stoller","name":"Rosé of Pinot Noir","vintage":"2025"}
            ]}
            """
        )
        assertEquals(1, found.size)
        assertEquals("Rosé of Pinot Noir", found.single().name)
    }

    @Test
    fun anImpossibleVintageOrAbvIsNotStored() {
        val found = parse(
            """
            {"bottles":[{"producer":"Ridge","name":"Geyserville","vintage":"1801","abv":"41"}]}
            """
        )
        assertEquals(null, found.single().vintage)
        assertEquals(null, found.single().alcohol)
    }

    @Test
    fun aNonVintageWineKeepsNoYear() {
        val found = parse(
            """
            {"bottles":[{"producer":"Billecart-Salmon","name":"Brut Rosé","vintage":"NV",
             "type":"Sparkling"}]}
            """
        )
        assertEquals(null, found.single().vintage)
        assertEquals(WineType.SPARKLING, found.single().type)
    }

    @Test
    fun oneBottlingIsListedOnce() {
        val found = parse(
            """
            {"bottles":[
              {"producer":"Stoller","name":"Rosé of Pinot Noir","vintage":"2025","type":"Rosé"},
              {"producer":"stoller","name":"Rosé of Pinot Noir","vintage":"2025","type":"Rosé"}
            ]}
            """
        )
        assertEquals(1, found.size)
    }

    @Test
    fun anEmptyAnswerIsARealAnswer() {
        assertTrue(parse("""{"bottles":[]}""").isEmpty())
        assertTrue(parse("""{"bottles": []}""").isEmpty())
    }

    @Test
    fun proseAroundTheJsonIsIgnored() {
        val found = parse(
            """
            Here are the bottles I could name:
            ```json
            {"bottles":[{"producer":"Stoller","name":"Rosé of Pinot Noir","vintage":"2025"}]}
            ```
            """
        )
        assertEquals(1, found.size)
    }

    @Test
    fun fourAtMost() {
        val entries = (2019..2025).joinToString(",") {
            """{"producer":"Stoller","name":"Rosé $it","vintage":"$it"}"""
        }
        assertEquals(BottleSearch.MAX, parse("""{"bottles":[$entries]}""").size)
    }

    @Test
    fun theEvidenceCarriesTheSnippetsAndNothingElse() {
        val evidence = BottleSearch.evidence(
            listOf(
                WebLookup.Hit(
                    title = "2025 Stoller Rosé of Pinot Noir",
                    url = "https://www.stollerwine.com/wines/2025-rose",
                    snippet = "Rosé of Pinot Noir, Dundee Hills, 12.5%"
                )
            )
        )
        assertTrue(evidence.contains("2025 Stoller Rosé of Pinot Noir"))
        assertTrue(evidence.contains("https://www.stollerwine.com/wines/2025-rose"))
        assertTrue(evidence.contains("12.5%"))
        assertTrue(BottleSearch.prompt("2025 stoller rose").contains("2025 stoller rose"))
    }
}
