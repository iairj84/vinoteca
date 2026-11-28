package com.wineverdict.app.data

import com.wineverdict.app.model.Verdict
import com.wineverdict.app.model.Wine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Correcting a night in the cellar book, and what that reaches.
 *
 * The book is the user's record of the evening; the bottle's verdict and dated line are what the
 * taste brief reads. A correction has to move both, and only where they are still the night's to
 * move — that is what most of these are about.
 */
class JournalEditTest {

    private val zone = ZoneId.systemDefault()

    private fun night(daysAgo: Long): Long =
        LocalDate.now().minusDays(daysAgo).atTime(20, 0)
            .atZone(zone).toInstant().toEpochMilli()

    private fun day(millis: Long): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    private fun bottle(
        rating: Int? = Verdict.GOOD.rating,
        notes: String = "",
        flavors: List<String> = emptyList()
    ) = Wine(
        id = "w",
        producer = "Giacomo Conterno",
        name = "Barolo",
        rating = rating,
        myNotes = notes,
        flavorTags = flavors
    )

    @Test
    fun aCorrectionReplacesTheNightTheVerdictAndTheLine() {
        val before = Drink(id = "a", title = "2019 Barolo", at = night(2), note = "old line")
        val after = before.corrected(
            at = night(6),
            rating = Verdict.LOVED.rating,
            note = "  tar and roses, opened early  ",
            flavors = listOf("Cherry", "  Tar ", "Cherry"),
            again = true
        )

        assertEquals(day(night(6)), day(after.at))
        assertEquals(Verdict.LOVED.rating, after.rating)
        assertEquals("tar and roses, opened early", after.note)
        assertEquals(listOf("Cherry", "Tar"), after.flavors)
        assertEquals(true, after.again)
    }

    @Test
    fun theEntryKeepsItsIdItsWineAndHowTheBottleLeft() {
        // A correction says what was written was wrong, not that a different bottle was opened.
        val before = Drink(
            id = "a",
            wineId = "w",
            title = "2019 Barolo",
            at = night(3),
            rating = Verdict.FINE.rating,
            via = Drink.DRANK
        )
        val after = before.corrected(night(5), Verdict.GOOD.rating, "", emptyList(), null)

        assertEquals("a", after.id)
        assertEquals("w", after.wineId)
        assertEquals(Drink.DRANK, after.via)
    }

    @Test
    fun aVerdictTypedOntoACountAdjustmentMakesItANight() {
        // The minus button asked nothing, so an entry that carries a word now is one the user gave.
        val count = Drink(id = "c", at = night(1), via = Drink.COUNT)
        assertEquals(Drink.MANUAL, count.corrected(night(1), Verdict.GOOD.rating, "", emptyList(), null).via)
        // Still nothing said is still a corrected count, not a silent verdict.
        assertEquals(Drink.COUNT, count.corrected(night(1), null, "", emptyList(), null).via)
    }

    @Test
    fun aHandWrittenNightIsCarriedToItsBottle() {
        val before = Drink(
            id = "a",
            wineId = "w",
            title = "2019 Barolo",
            at = night(3),
            rating = Verdict.GOOD.rating,
            note = "tar and roses",
            via = Drink.MANUAL
        )
        val wine = bottle(notes = "${day(before.at)} — tar and roses")
        val after = before.corrected(
            at = night(5),
            rating = Verdict.LOVED.rating,
            note = "tar, roses, still tannic",
            flavors = listOf("Cherry"),
            again = true
        )

        val fixed = correctBottle(wine, before, after, zone)

        assertEquals(Verdict.LOVED.rating, fixed.rating)
        assertTrue(fixed.favorite)
        assertEquals("the line is rewritten where it stands", listOf("${day(after.at)} — tar, roses, still tannic"), fixed.myNotes.lines())
        assertEquals(listOf("Cherry"), fixed.flavorTags)
    }

    @Test
    fun aLaterVerdictOnTheBottleIsNotOverwritten() {
        // Night one said Fine; a later night said Loved, so the bottle holds Loved. Correcting the
        // first night's line must not pull the heart back to the older answer.
        val before = Drink(id = "a", wineId = "w", at = night(30), rating = Verdict.FINE.rating)
        val wine = bottle(rating = Verdict.LOVED.rating)
        val after = before.corrected(night(30), Verdict.GOOD.rating, "", emptyList(), null)

        val fixed = correctBottle(wine, before, after, zone)

        assertEquals(Verdict.LOVED.rating, fixed.rating)
        assertTrue(fixed.favorite)
    }

    @Test
    fun takingTheVerdictBackClearsTheBottleOnlyWhenItStillHoldsIt() {
        val before = Drink(id = "a", wineId = "w", at = night(4), rating = Verdict.NOT_FOR_ME.rating)

        val cleared = correctBottle(bottle(rating = Verdict.NOT_FOR_ME.rating), before, before.corrected(night(4), null, "", emptyList(), null), zone)
        assertNull(cleared.rating)

        val kept = correctBottle(bottle(rating = Verdict.LOVED.rating), before, before.corrected(night(4), null, "", emptyList(), null), zone)
        assertEquals(Verdict.LOVED.rating, kept.rating)
    }

    @Test
    fun aNoteTypedOnTheBottlePageIsNotRewritten() {
        // The line the night wrote is gone — edited by hand on the bottle's page — so nothing here
        // is the correction's to replace, and a second copy would read as a second tasting.
        val before = Drink(id = "a", wineId = "w", at = night(3), note = "tar and roses", via = Drink.MANUAL)
        val wine = bottle(notes = "${day(before.at)} — tar, roses, still tannic")
        val after = before.corrected(night(3), null, "opened too late", emptyList(), null)

        val fixed = correctBottle(wine, before, after, zone)

        assertEquals(wine.myNotes, fixed.myNotes)
    }

    @Test
    fun aNightThatHadNoLineGainsOne() {
        val before = Drink(id = "a", wineId = "w", at = night(2), rating = Verdict.GOOD.rating)
        val after = before.corrected(night(2), Verdict.GOOD.rating, "second glass was better", emptyList(), null)

        val fixed = correctBottle(bottle(notes = ""), before, after, zone)

        assertEquals("${day(after.at)} — second glass was better", fixed.myNotes)
    }

    @Test
    fun aClearedLineIsTakenOffTheBottle() {
        val before = Drink(id = "a", wineId = "w", at = night(2), note = "wrong bottle entirely", via = Drink.MANUAL)
        val wine = bottle(notes = "notes from a page\n${day(before.at)} — wrong bottle entirely")
        val after = before.corrected(night(2), null, "", emptyList(), null)

        val fixed = correctBottle(wine, before, after, zone)

        assertEquals("notes from a page", fixed.myNotes)
        assertFalse(fixed.myNotes.contains("wrong bottle entirely"))
    }
}
