package com.wineverdict.app.data

import com.wineverdict.app.model.Wine
import com.wineverdict.app.model.WineType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The seven defects one ticket, one test each.
 *
 * Every case runs offline: no device, no emulator, no provider key. That is the point of turning the
 * prompt into a renderer — the prompt can now be checked without asking a model what it thinks.
 */
class GateRegistryTest {

    private fun profile(
        ceiling: Double? = 14.0,
        sweet: String = QuickStart.SWEET_NONE,
        links: String = ""
    ) = PalateProfile(
        answers = buildMap {
            put(QuickStart.Q_POURS, listOf(QuickStart.POUR_RED))
            put(QuickStart.Q_SWEET, listOf(sweet))
            put(QuickStart.Q_BUDGET, listOf(QuickStart.BUDGET_20_35))
        },
        alcoholCeiling = ceiling,
        links = links
    )

    private fun verdicts(row: GateRow, dish: Dish? = null) =
        GateRegistry.evaluate(row, dish, GateRegistry.specs(alcoholCeiling = 14.0, sugarCeiling = 4.0))

    private fun verdict(id: GateId, row: GateRow, dish: Dish? = null) =
        verdicts(row, dish).first { it.gate == id.title }

    private fun alcohol(row: GateRow, dish: Dish? = null, ceiling: Double = 14.0) =
        GateRegistry.evaluate(row, dish, GateRegistry.specs(alcoholCeiling = ceiling))
            .first { it.gate == GateId.G1_ALCOHOL.title }

    private fun bottle(
        producer: String = "Prospice",
        name: String = "Cabernet Franc",
        rating: Int? = 5,
        type: WineType = WineType.RED,
        flavorTags: List<String> = emptyList(),
        expertNotes: String = "",
        notesSource: String = ""
    ) = Wine(
        producer = producer,
        name = name,
        vintage = 2019,
        type = type,
        region = "Walla Walla",
        country = "USA",
        grapes = listOf("Cabernet Franc"),
        rating = rating,
        flavorTags = flavorTags,
        expertNotes = expertNotes,
        notesSource = notesSource
    )

    // ---- D1: four gates, authored order, one registry ---------------------------------------

    @Test
    fun theRegistryIsFourGatesInTheAuthoredOrder() {
        val specs = GateRegistry.specs(alcoholCeiling = 14.0, sugarCeiling = 4.0)
        assertEquals(
            listOf("G1 Alcohol", "G2 Place", "G3 Dry", "G4 Style words"),
            specs.map { it.title }
        )
        assertEquals(GateRegistry.order.map { it.title }, specs.map { it.title })

        val rows = PalateFile.build(profile()).lines().filter { it.startsWith("| **G") }
        assertEquals("the table must not skip a gate again", 4, rows.size)
        assertEquals(
            listOf("G1", "G2", "G3", "G4"),
            rows.map { it.removePrefix("| **").substringBefore(" ") }
        )
    }

    // ---- D2: the alcohol tiers --------------------------------------------------------------

    @Test
    fun fourteenZeroExactlyIsAPass() {
        assertEquals(GateVerdict.PASS, alcohol(GateRow(abv = 14.0)).verdict)
    }

    @Test
    fun fourteenTwoWithACoolSiteIsConditionalAndSaysTheNumber() {
        val v = alcohol(GateRow(abv = 14.2, hasCoolSite = true))
        assertEquals(GateVerdict.CONDITIONAL, v.verdict)
        assertTrue(v.detail, v.detail.contains("14.2"))
        assertTrue("a conditional pass is orderable", GateRegistry.isOrderable(listOf(v)))
    }

    @Test
    fun fourteenTwoWithFoodIsConditional() {
        assertEquals(
            GateVerdict.CONDITIONAL,
            alcohol(GateRow(abv = 14.2), Dish("roast chicken")).verdict
        )
    }

    @Test
    fun fourteenTwoWithNeitherIsAFail() {
        val v = alcohol(GateRow(abv = 14.2))
        assertEquals(GateVerdict.FAIL, v.verdict)
        assertTrue(v.detail, v.detail.contains("no cool site and no food"))
    }

    @Test
    fun fourteenSixIsOverTheCeiling() {
        assertEquals(GateVerdict.FAIL, alcohol(GateRow(abv = 14.6)).verdict)
    }

    @Test
    fun anUnknownAbvIsUnknownNotPassAndSaysSo() {
        val v = alcohol(GateRow(abv = null))
        assertEquals(GateVerdict.UNKNOWN, v.verdict)
        assertFalse("an unknown bottle does not make the ranked picks", GateRegistry.isOrderable(listOf(v)))
        assertTrue(v.detail, v.detail.contains("ABV unknown — check the label"))
    }

    @Test
    fun offDryRieslingIsConditionalWithSpiceAndFailsWithChicken() {
        val riesling = GateRow.of(
            bottle(name = "Riesling", type = WineType.WHITE, flavorTags = listOf("off-dry"))
        )
        assertEquals(9.0, riesling.sugarGL!!, 0.001)
        assertEquals(
            GateVerdict.CONDITIONAL,
            verdict(GateId.G3_DRY, riesling, Dish("chicken tikka", spicy = true)).verdict
        )
        assertEquals(
            GateVerdict.FAIL,
            verdict(GateId.G3_DRY, riesling, Dish("roast chicken")).verdict
        )
    }

    // ---- D1: G2 and G4, exactly as authored -------------------------------------------------

    @Test
    fun aBroadAppellationWithNoVineyardAboveTenIsAFail() {
        assertEquals(
            GateVerdict.FAIL,
            verdict(GateId.G2_PLACE, GateRow(appellation = "Napa Valley", priceUsd = 25.0)).verdict
        )
        assertEquals(
            GateVerdict.PASS,
            verdict(GateId.G2_PLACE, GateRow(vineyard = "To Kalon Vineyard", priceUsd = 55.0)).verdict
        )
        assertEquals(
            GateVerdict.PASS,
            verdict(GateId.G2_PLACE, GateRow(appellation = "Walla Walla", priceUsd = 60.0)).verdict
        )
        assertEquals(
            "an unlisted place never defaults to a pass",
            GateVerdict.UNKNOWN,
            verdict(GateId.G2_PLACE, GateRow(appellation = "Languedoc", priceUsd = 8.0)).verdict
        )
    }

    @Test
    fun theStyleGateUsesTheAuthoredWordsAndNothingElse() {
        assertEquals(
            GateVerdict.FAIL,
            verdict(GateId.G4_STYLE, GateRow(description = "jammy and plush")).verdict
        )
        assertEquals(
            GateVerdict.PASS,
            verdict(GateId.G4_STYLE, GateRow(description = "peppery and savory")).verdict
        )
        val neither = verdict(GateId.G4_STYLE, GateRow(description = "a red wine"))
        assertEquals(GateVerdict.UNKNOWN, neither.verdict)
        assertFalse(GateRegistry.isOrderable(listOf(neither)))
        // Restrained vanilla and toast are wanted; only "sweet oak" is rejected.
        assertFalse(GateRegistry.STYLE_FAIL.contains("vanilla"))
        assertFalse(GateRegistry.STYLE_FAIL.contains("toast"))
    }

    // ---- D6: one render path, every line carries a field ------------------------------------

    @Test
    fun everyPromptLineMapsToAFieldAndAHandWrittenLineCannotSurvive() {
        val p = profile(links = "Pinot's lift with more grip.")
        val prompt = PalateFile.renderModelPrompt(p)
        PalateFile.assertEveryLineRendered(prompt)
        assertEquals(prompt.text.split("\n").size, prompt.lineFields.size)
        prompt.lineFields.filterNotNull().forEach { field ->
            assertNotEquals(
                "an absent field must not reach a model: $field",
                Confidence.UNKNOWN,
                PalateFile.fieldConfidence(p, field)
            )
        }

        val injected = PalateFile.RenderedPrompt(
            prompt.text + "\nA sentence nobody stored.",
            prompt.lineFields + null
        )
        try {
            PalateFile.assertEveryLineRendered(injected)
            fail("a hand-written prompt line must not survive")
        } catch (expected: IllegalStateException) {
            // The guard is the requirement.
        }
    }

    @Test
    fun aNoteWithLineBreaksStaysOneFieldAndOneLine() {
        // The note fields are multi-line boxes, so pressing Enter is ordinary use. A newline left
        // inside a field made one field cover several lines and the count refused the prompt: the
        // Taste tab died with "prompt has 29 lines but 24 field ids".
        val p = profile(links = "Pinot's lift.\nMore grip.")
            .withNotes(
                loved = "2019 Ultreia\nforest floor\nand dark fruit",
                disliked = "jammy red\nno acid",
                curious = "white Rhône"
            )
        val prompt = PalateFile.renderModelPrompt(p)
        assertEquals("one line per field", prompt.text.split("\n").size, prompt.lineFields.size)
        assertTrue(prompt.text, prompt.text.contains("2019 Ultreia forest floor and dark fruit"))
        assertTrue(prompt.text, prompt.text.contains("Pinot's lift. More grip."))
    }

    @Test
    fun aHardGateIsNeverCalledAPreference() {
        val hard = PalateFile.renderModelPrompt(profile(ceiling = 14.0)).text
        assertTrue(hard, hard.contains("Gates: hard. A bottle that fails one is not offered."))
        assertFalse(hard, hard.contains("Preferences, not rules"))

        val soft = PalateFile.renderModelPrompt(profile(ceiling = null)).text
        assertFalse(soft, soft.contains("Gates: hard."))
        assertTrue(soft, soft.contains("Preferences, not rules"))
    }

    @Test
    fun thePromptCarriesNoNumberTheProfileDoesNotHold() {
        val prompt = PalateFile.renderModelPrompt(profile())
        val percents = Regex("""\d+\.\d+%""").findAll(prompt.text).map { it.value }.toSet()
        assertEquals(setOf("14.0%"), percents)
        // The restaurant band is arithmetic on his retail band, so it is prefixed derived:.
        val derived = prompt.text.split("\n")[prompt.lineFields.indexOf(PalateFile.FIELD_RESTAURANT)]
        assertTrue(derived, derived.startsWith("derived:"))
        assertTrue(derived, derived.contains("\$50–123"))
    }

    @Test
    fun theFileNamesTheAllowanceBesideTheCeiling() {
        // A table that says "14.0% or under" and "a hard gate cannot be broken" reads as an absolute
        // rule, so the model refused a 14.2% bottle the digest had marked conditional for the food.
        val file = PalateFile.build(profile(ceiling = 14.0))
        val row = file.lines().first { it.contains("G1 Alcohol") }
        assertTrue(row, row.contains("up to 14.5% only with a cool site or food"))
        assertTrue(row, row.contains("over 14.5% is out"))
        assertFalse(
            "the allowance is part of the rule, so the note cannot call the gate unbreakable",
            file.contains("A **hard** gate cannot be broken.")
        )
    }

    // ---- D4: confidence is computed ----------------------------------------------------------

    @Test
    fun confidenceComesFromWhereTheValueCameFrom() {
        val specs = GateRegistry.specs(alcoholCeiling = 14.0, sugarCeiling = 4.0)
        assertEquals(
            Confidence.YOURS,
            specs.first { it.id == GateId.G1_ALCOHOL }.confidence
        )
        assertEquals(
            "the 4.0 g/L threshold is our number, whatever the answer behind it",
            Confidence.OURS,
            specs.first { it.id == GateId.G3_DRY }.confidence
        )
        val oak = PalateProfile(
            answers = mapOf(QuickStart.Q_AVOID to listOf(QuickStart.AVOID_OAK))
        )
        val file = PalateFile.build(oak, ratings = 0)
        val oakLine = file.lines().first { it.contains("Oak restrained") }
        assertTrue(oakLine, oakLine.endsWith("Ours*"))
        val dryRow = PalateFile.build(profile()).lines().first { it.contains("G3 Dry") }
        assertTrue("a row labelled Yours for a number he never gave", dryRow.endsWith("Ours |"))
    }

    // ---- D5: the anchor survives ------------------------------------------------------------

    @Test
    fun aWrittenAnchorSurvivesAndOnlyItsAbsenceSaysNotSetYet() {
        val sentence = "Pinot's lift with more grip."
        val withAnchor = PalateFile.build(profile(links = sentence), ratings = 2)
        assertTrue(withAnchor.contains(sentence))
        assertFalse(withAnchor.contains("Not set yet"))

        val without = PalateFile.build(profile(), ratings = 2)
        assertTrue(without.contains("Not set yet"))
        assertTrue(
            "it must name the missing input, not the generator's silence",
            without.contains("what links them")
        )
    }

    // ---- D7: a bottle note needs a source ----------------------------------------------------

    @Test
    fun anUnattributedBottleNoteKeepsItsFlavourClaimsOut() {
        val claim = "This wine is characterized by a sense of weightless intensity"
        val unattributed = PalateFile.lovedSection(listOf(bottle(expertNotes = claim)))
        assertFalse(unattributed, unattributed.contains("weightless"))
        assertTrue(unattributed, unattributed.contains("Prospice"))
        assertTrue(unattributed, unattributed.contains("Walla Walla"))

        val sourced = PalateFile.lovedSection(
            listOf(
                bottle(
                    expertNotes = claim,
                    notesSource = "https://example.com/tasting-note"
                ).copy(notesCheckedAt = 1_700_000_000_000)
            )
        )
        assertTrue(sourced, sourced.contains("weightless"))
        assertTrue(sourced, sourced.contains("source: https://example.com/tasting-note"))
        assertTrue(sourced, sourced.contains("retrieved "))
    }
}
