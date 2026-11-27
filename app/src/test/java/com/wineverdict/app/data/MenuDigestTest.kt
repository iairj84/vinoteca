package com.wineverdict.app.data

import com.wineverdict.app.model.Verdict
import com.wineverdict.app.model.Wine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * What the chat reads about a restaurant. It has to name the place and the day, say what was ordered
 * there, and keep the wines refused on that menu out of its own suggestions — by identity, because
 * the verdict may live on the cellar record rather than on the menu's copy.
 */
class MenuDigestTest {

    private fun wine(
        id: String,
        producer: String = "Elowen",
        name: String = "Viognier",
        vintage: Int? = 2024,
        quantity: Int = 0,
        rating: Int? = null
    ) = Wine(
        id = id,
        producer = producer,
        name = name,
        vintage = vintage,
        quantity = quantity,
        rating = rating
    )

    private fun daysAgo(days: Long): Long =
        LocalDate.now()
            .minusDays(days)
            .atTime(20, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    private val osteria = SavedList(
        id = "list-1",
        name = "Osteria",
        savedAt = daysAgo(2),
        chosen = listOf("menu-picked"),
        wines = listOf(
            wine("menu-picked", producer = "Gran Moraine", name = "Yamhill", vintage = 2022),
            wine("menu-refused", producer = "Bodegas", name = "Rioja Reserva", vintage = 2012),
            wine("menu-other", producer = "Domaine", name = "Chablis", vintage = 2021)
        )
    )

    @Test
    fun thePlaceTheDayAndWhatWasOrdered() {
        val picked = wine("own-picked", producer = "Gran Moraine", name = "Yamhill", vintage = 2022, rating = Verdict.LOVED.rating)
        val digest = menuDigest(listOf(osteria), listOf(picked), now = daysAgo(0))

        assertTrue(digest, digest.contains("Osteria"))
        assertTrue(digest, digest.contains(dayWords(daysAgo(2), daysAgo(0))))
        assertTrue(digest, digest.contains("3 wines read"))
        // The verdict is read off the cellar record under a different id, and named with the pick.
        assertTrue(digest, digest.contains("2022 Yamhill · Gran Moraine (Loved)"))
    }

    @Test
    fun aRefusalOnTheMenuIsStatedAsAFact() {
        val refused = wine("own-refused", producer = "Bodegas", name = "Rioja Reserva", vintage = 2012, rating = Verdict.NOT_FOR_ME.rating)
        val digest = menuDigest(listOf(osteria), listOf(refused))

        assertTrue(digest, digest.contains("not for me there: 2012 Rioja Reserva · Bodegas"))
        // And it is not also listed as something that was ordered: one pick, not two.
        assertFalse(digest, digest.contains("picked 2"))
    }

    @Test
    fun theNewestMenusComeFirstAndTheRestAreCapped() {
        val older = osteria.copy(id = "list-2", name = "Trattoria", savedAt = daysAgo(40))
        val digest = menuDigest(listOf(osteria, older), emptyList(), limit = 1)

        assertTrue(digest, digest.contains("Osteria"))
        assertFalse(digest, digest.contains("Trattoria"))
        assertEquals(1, digest.lines().size)
    }

    @Test
    fun noMenusIsBlankRatherThanAPlaceholder() {
        assertEquals("", menuDigest(emptyList(), emptyList()))
    }
}
