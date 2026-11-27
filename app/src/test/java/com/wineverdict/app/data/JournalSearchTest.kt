package com.wineverdict.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a search of the cellar book matches.
 *
 * The words searched are the ones a row actually shows, every word has to appear, and an empty
 * search is the whole book rather than nothing — so typing a second word narrows the record.
 */
class JournalSearchTest {

    private val entry = Drink(
        title = "2019 Barolo Riserva",
        type = "Red",
        region = "Piedmont",
        note = "Tar and roses, opened late",
        flavors = listOf("Cherry", "Tobacco")
    )

    @Test
    fun anEmptySearchKeepsEverything() {
        assertTrue(matchesWords(entry, ""))
        assertTrue(matchesWords(entry, "   "))
    }

    @Test
    fun theLabelIsSearchableByAnyPart() {
        assertTrue(matchesWords(entry, "barolo"))
        assertTrue(matchesWords(entry, "2019"))
    }

    @Test
    fun theRegionTheNoteAndTheTasteWordsCount() {
        assertTrue(matchesWords(entry, "piedmont"))
        assertTrue(matchesWords(entry, "roses"))
        assertTrue(matchesWords(entry, "tobacco"))
    }

    @Test
    fun everyWordHasToMatch() {
        assertTrue(matchesWords(entry, "barolo piedmont"))
        assertFalse(matchesWords(entry, "barolo burgundy"))
        assertFalse(matchesWords(entry, "chablis"))
    }

    @Test
    fun caseAndPaddingDoNotMatter() {
        assertTrue(matchesWords(entry, "  BaRoLo  "))
    }
}
