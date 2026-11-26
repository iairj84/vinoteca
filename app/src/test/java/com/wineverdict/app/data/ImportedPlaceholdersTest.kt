package com.wineverdict.app.data

import com.wineverdict.app.model.Wine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The placeholder a spreadsheet writes when it has no value: "Unknown" in a Designation column is
 * not a vineyard, and a name with it glued on is a wine that does not exist.
 */
class ImportedPlaceholdersTest {

    private fun imported(csv: String): ImportPreview =
        WineImport.fromCsv(csv.trimIndent(), "USD")

    @Test
    fun aPlaceholderDesignationIsNotPartOfTheName() {
        val preview = imported(
            """
            Wine,Producer,Vintage,Designation,Quantity
            Prospice Cabernet Sauvignon Seven Hills Vineyard,Prospice,2020,Unknown,1
            """
        )
        assertEquals(
            "Prospice Cabernet Sauvignon Seven Hills Vineyard",
            preview.rows.single().wine.name
        )
    }

    @Test
    fun aRealDesignationStillJoinsTheName() {
        val preview = imported(
            """
            Wine,Producer,Vintage,Designation,Quantity
            Ridge Zinfandel,Ridge,2019,Papera Vineyard,1
            """
        )
        assertEquals("Ridge Zinfandel Papera Vineyard", preview.rows.single().wine.name)
    }

    @Test
    fun aNameThatArrivedWithTheWordOnItIsCleaned() {
        val preview = imported(
            """
            Wine,Producer,Vintage,Designation,Quantity
            Ridge Geyserville Unknown,Ridge,2019,Unknown,1
            """
        )
        assertEquals("Ridge Geyserville", preview.rows.single().wine.name)
    }

    @Test
    fun aPlaceholderPlaceOrGrapeIsReadAsBlank() {
        val preview = imported(
            """
            Wine,Producer,Vintage,Country,Region,Appellation,Grapes,Quantity
            Ridge Geyserville,Ridge,2019,Unknown,N/A,None,Unknown,1
            """
        )
        val wine = preview.rows.single().wine
        assertEquals("", wine.country)
        assertEquals("", wine.region)
        assertEquals("", wine.appellation)
        assertTrue(wine.grapes.isEmpty())
    }

    @Test
    fun aStoredBottleLosesTheWordAndKeepsItsRealFacts() {
        val stored = Wine(
            producer = "Prospice",
            name = "Prospice Cabernet Sauvignon Seven Hills Vineyard Unknown",
            vintage = 2020,
            country = "USA",
            region = "Washington",
            appellation = "Walla Walla Valley",
            grapes = listOf("Cabernet Sauvignon", "Unknown")
        )
        val cleaned = stored.cleanPlaceholders()
        assertEquals("Prospice Cabernet Sauvignon Seven Hills Vineyard", cleaned.name)
        assertEquals("USA", cleaned.country)
        assertEquals("Washington", cleaned.region)
        assertEquals("Walla Walla Valley", cleaned.appellation)
        assertEquals(listOf("Cabernet Sauvignon"), cleaned.grapes)
        // The vintage and the count are facts nobody asked about; the repair does not touch them.
        assertEquals(2020, cleaned.vintage)
        assertEquals(stored.quantity, cleaned.quantity)
    }

    @Test
    fun aStoredPlaceThatWasOnlyAFillerIsBlanked() {
        val stored = Wine(
            producer = "Ridge",
            name = "Geyserville",
            country = "Unknown",
            region = "N/A",
            appellation = "-"
        )
        val cleaned = stored.cleanPlaceholders()
        assertEquals("", cleaned.country)
        assertEquals("", cleaned.region)
        assertEquals("", cleaned.appellation)
    }

    @Test
    fun aCleanBottleIsUntouched() {
        val clean = Wine(
            producer = "Ridge",
            name = "Geyserville",
            country = "USA",
            region = "Sonoma",
            grapes = listOf("Zinfandel")
        )
        assertEquals(clean, clean.cleanPlaceholders())
        val cellar = com.wineverdict.app.model.CellarData(wines = listOf(clean))
        assertEquals(cellar, cellar.withPlaceholdersCleared())
    }
}
