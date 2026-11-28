package com.wineverdict.app.data

import com.wineverdict.app.model.CellarData
import com.wineverdict.app.model.StorageLocation
import com.wineverdict.app.model.Wine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What both search boxes answer.
 *
 * A bottle is spelled the way the label spells it, and a thumb types the way a phone keyboard types:
 * the box has to meet in the middle. It still matches parts of words and still requires every word,
 * because those two rules are what make a second word narrow the list.
 */
class SearchTest {

    @Test
    fun accentsFoldAway() {
        assertEquals("rose", Search.fold("Rosé"))
        assertEquals("cote rotie", Search.fold("Côte-Rôtie"))
        assertEquals("st estephe", Search.fold("St. Estèphe"))
        assertEquals("2019 barolo", Search.fold("2019—Barolo"))
    }

    @Test
    fun anApostropheIsAnElisionRatherThanABreak() {
        assertEquals("dyquem", Search.fold("d'Yquem"))
        assertTrue(Search.matches(listOf("d'Yquem"), "dyquem"))
        assertTrue(Search.matches(listOf("d'Yquem"), "d'yquem"))
        assertTrue(Search.matches(listOf("d'Yquem"), "yquem"))
    }

    @Test
    fun theSpellingTypedIsNotTheThingTested() {
        assertTrue(Search.matches(listOf("  Chardonnay!  "), "chardonnay"))
        assertTrue(Search.matches(listOf("Côtes du Rhône"), "COTES"))
        assertTrue(Search.matches(listOf("Rosé"), "ROSÉ"))
    }

    @Test
    fun aSecondWordNarrowsRatherThanWidens() {
        val fields = listOf("Barbaresco", "Produttori", "Piedmont", "Nebbiolo")

        assertTrue(Search.matches(fields, "nebbiolo"))
        assertTrue(Search.matches(fields, "nebbiolo piedmont"))
        assertFalse(Search.matches(fields, "nebbiolo burgundy"))
    }

    @Test
    fun anEmptyBoxIsTheWholeShelf() {
        assertTrue(Search.matches(listOf("Barolo"), ""))
        assertTrue(Search.matches(listOf("Barolo"), "   "))
        assertTrue(Search.matches(listOf("Barolo"), "!?"))
    }

    @Test
    fun theCellarsBoxReadsTheBottleAndItsShelf() {
        val slot = StorageLocation(id = "l1", name = "Garage fridge")
        val rose = Wine(
            name = "Rosé",
            region = "Provence",
            grapes = listOf("Grenache", "Cinsault"),
            locationId = "l1",
            slot = "Top shelf",
            myNotes = "Dry, pale"
        )
        val cellar = CellarData(wines = listOf(rose), locations = listOf(slot))
        val fields = Search.wineFields(rose, cellar)

        assertTrue(Search.matches(fields, "rose"))
        assertTrue(Search.matches(fields, "provence cinsault"))
        assertTrue(Search.matches(fields, "garage"))
        assertTrue(Search.matches(fields, "top shelf dry"))
        assertFalse(Search.matches(fields, "barolo"))
    }
}
