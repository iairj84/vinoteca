package com.wineverdict.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceholdersTest {

    @Test
    fun theWordsAnExportWritesForNothingAreFiller() {
        listOf("Unknown", "unknown", " N/A ", "n.a.", "None", "null", "-", "—", "?").forEach {
            assertTrue("\"$it\" should read as filler", Placeholders.isFiller(it))
        }
    }

    @Test
    fun aRealPlaceIsNotFiller() {
        listOf("Walla Walla Valley", "Seven Hills Vineyard", "Unknown Wine Co.", "", "Nonesuch")
            .forEach { assertFalse("\"$it\" is a value", Placeholders.isFiller(it)) }
    }

    @Test
    fun aTrailingPlaceholderComesOffTheName() {
        assertEquals(
            "Prospice Cabernet Sauvignon Seven Hills Vineyard",
            Placeholders.stripTrailing("Prospice Cabernet Sauvignon Seven Hills Vineyard Unknown")
        )
        assertEquals("Ridge Geyserville", Placeholders.stripTrailing("Ridge Geyserville N/A"))
        assertEquals("Ridge Zinfandel Papera", Placeholders.stripTrailing("Ridge Zinfandel Papera -"))
        assertEquals("Ridge Geyserville", Placeholders.stripTrailing("Ridge Geyserville Unknown Unknown"))
    }

    @Test
    fun aRealNameIsLeftExactlyAsItIs() {
        listOf(
            "Seven Hills Vineyard",
            "Unknown Wine Co.",
            "Les Collines Vineyard",
            "Château d'Yquem"
        ).forEach { assertEquals(it, Placeholders.stripTrailing(it)) }
    }

    @Test
    fun aNameThatWasOnlyFillerBecomesBlank() {
        assertEquals("", Placeholders.stripTrailing("Unknown"))
        assertEquals("", Placeholders.stripTrailing("  N/A "))
    }
}
