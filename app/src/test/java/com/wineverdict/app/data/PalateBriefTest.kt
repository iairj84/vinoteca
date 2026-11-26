package com.wineverdict.app.data

import com.wineverdict.app.model.Wine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The brief is the only taste text a model request carries, so these tests guard the two things
 * that made the old file useless as a prompt: its length, and the two rules that told the model
 * not to use what it knows about wine.
 */
class PalateBriefTest {

    private fun profile(
        budget: String = QuickStart.BUDGET_20_35,
        avoid: List<String> = listOf(QuickStart.AVOID_OAK, QuickStart.AVOID_STRONG)
    ) = PalateProfile(
        answers = mapOf(
            QuickStart.Q_POURS to listOf(QuickStart.POUR_RED),
            QuickStart.Q_STYLE to listOf(QuickStart.STYLE_SMOOTH),
            QuickStart.Q_SWEET to listOf(QuickStart.SWEET_NONE),
            QuickStart.Q_BUDGET to listOf(budget),
            QuickStart.Q_AVOID to avoid,
            QuickStart.Q_ADVENTURE to listOf(QuickStart.ADVENTURE_SURPRISE)
        )
    )

    private fun wine(i: Int, rating: Int? = null) = Wine(
        producer = "Producer $i",
        name = "Cuvee $i",
        vintage = 2015 + i,
        region = "Rioja",
        country = "Spain",
        rating = rating,
        flavorTags = listOf("dark fruit", "tobacco", "leather")
    )

    @Test
    fun staysUnderTheCapWithAFullCellar() {
        val brief = PalateFile.brief(
            profile = profile(),
            loved = (1..6).map { wine(it, rating = 5) },
            disliked = (7..11).map { wine(it, rating = 1) },
            links = "Smooth reds with acid, never jammy.",
            notes = "x".repeat(2_000)
        )
        println("BRIEF (${brief.length} chars):\n$brief")
        assertTrue("brief was ${brief.length} chars", brief.length <= PalateFile.BRIEF_MAX_CHARS)
    }

    @Test
    fun dropsTheRulesThatForbadeKnowledge() {
        val brief = PalateFile.brief(profile(), loved = listOf(wine(1, 5)))
        assertFalse(brief.contains("never from memory", ignoreCase = true))
        assertFalse(brief.contains("Unknown is not a pass", ignoreCase = true))
        // No gate tables, precedence rules or confidence vocabulary either.
        assertFalse(brief.contains("gate", ignoreCase = true))
        assertFalse(brief.contains("Confidence", ignoreCase = true))
        assertFalse(brief.contains("precedence", ignoreCase = true))
    }

    @Test
    fun statesOakAndAlcoholAsPreferences() {
        val brief = PalateFile.brief(profile())
        assertTrue(brief, brief.contains("I prefer"))
        assertTrue(brief, brief.contains("Preferences, not rules"))
        assertTrue(brief, brief.contains("restrained oak"))
        assertTrue(brief, brief.contains("moderate alcohol"))
    }

    @Test
    fun restaurantBudgetIsDerivedFromRetail() {
        val brief = PalateFile.brief(profile(budget = QuickStart.BUDGET_12_20))
        assertTrue(brief, brief.contains("\$12–20 a bottle at retail"))
        assertTrue(brief, brief.contains("about \$30–70 on a restaurant list"))
    }

    @Test
    fun lovedBottlesAreNamedWithTheirTags() {
        val brief = PalateFile.brief(profile(), loved = listOf(wine(1, 5)))
        assertTrue(brief, brief.contains("Producer 1 Cuvee 1 2016, Rioja — dark fruit, tobacco, leather"))
    }

    @Test
    fun notesAreCappedAtSixHundredCharacters() {
        val brief = PalateFile.brief(
            profile(),
            notes = "y".repeat(2_000),
            maxChars = 10_000
        )
        val section = brief.substringAfter("## Notes\n", "")
        assertEquals(600, section.trim().length)
    }

    @Test
    fun anEmptyProfileStillSaysSomethingHonest() {
        val brief = PalateFile.brief(PalateProfile.EMPTY)
        println("EMPTY BRIEF (${brief.length} chars):\n$brief")
        assertTrue(brief.isNotBlank())
        assertTrue(brief.length <= PalateFile.BRIEF_MAX_CHARS)
        assertTrue(brief, brief.contains("have not told you what I like yet"))
    }

    @Test
    fun theInterviewReachesTheBriefOnlyWhereItWasAnswered() {
        val brief = PalateFile.brief(
            profile()
                .withAnswer(QuickStart.X_GRAPES, listOf("pinot", "nebbiolo"))
                .withAnswer(QuickStart.X_TANNIN, listOf("grip"))
        )
        assertTrue(brief, brief.contains("Grapes I love: Pinot Noir, Nebbiolo."))
        assertTrue(brief, brief.contains("Tannin: real grip"))
        assertFalse(brief, brief.contains("Places I have liked"))
    }

    @Test
    fun anAppOnlyChipStaysOutOfTheBrief() {
        // "The names are still new to me" describes the user, not the wine: it must not reach a model.
        val brief = PalateFile.brief(profile().withAnswer(QuickStart.X_GRAPES, listOf("unsure")))
        assertFalse(brief, brief.contains("Grapes I love"))
    }

    @Test
    fun aFullyAnsweredInterviewStillFitsTheCap() {
        var p = profile()
        QuickStart.extras.forEach { question ->
            p = p.withAnswer(question.id, question.options.take(2).map { it.id })
        }
        val brief = PalateFile.brief(
            profile = p,
            loved = (1..4).map { wine(it, 5) },
            disliked = (7..9).map { wine(it, 1) },
            links = "Smooth reds with acid, never jammy.",
            notes = "z".repeat(600)
        )
        println("FULL BRIEF (${brief.length} chars):\n$brief")
        assertTrue("brief was ${brief.length} chars", brief.length <= PalateFile.BRIEF_MAX_CHARS)
        // The budget is the last essential line, so it is the canary for a cut that ate too much.
        assertTrue(brief, brief.contains("Budget:"))
    }

    @Test
    fun notesTheUserTypedIntoTheFileReachTheBrief() {
        val file = """
            # My taste

            ## What I enjoy
            - **Body:** medium to full

            ## Notes for the assistant
            - Prefer bottles I already own for dinner.

            ## Notes from my previous file (kept)
            - I like Bierzo.
        """.trimIndent()
        val notes = TasteText.userNotes(file)
        assertTrue(notes, notes.contains("Prefer bottles I already own"))
        assertTrue(notes, notes.contains("I like Bierzo"))
        assertFalse("the generated half is already rendered from the profile", notes.contains("Body:"))
    }

    @Test
    fun aTighterCapIsStillHonoured() {
        val brief = PalateFile.brief(
            profile(),
            loved = (1..4).map { wine(it, 5) },
            maxChars = 200
        )
        assertTrue("brief was ${brief.length} chars", brief.length <= 200)
        assertTrue(brief.endsWith("…"))
    }
}
