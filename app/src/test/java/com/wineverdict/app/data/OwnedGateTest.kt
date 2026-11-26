package com.wineverdict.app.data

import com.wineverdict.app.model.Wine
import com.wineverdict.app.model.WineType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A hard gate is a limit on the shelf and advice at home. The same bottle, judged twice, is the
 * whole point: a 14.9% bottle the user already owns is a glass to drink, and the same bottle in a
 * shop is one to leave.
 */
class OwnedGateTest {

    private val rules = PalateRules(maxAlcohol = 14.0)

    private fun bottle(abv: Double?, notes: String = "peppery and savory, cool-site fruit") = Wine(
        id = "w",
        producer = "Prospice",
        name = "Syrah Les Collines Vineyard",
        vintage = 2021,
        type = WineType.RED,
        appellation = "Walla Walla Valley",
        alcohol = abv,
        price = 38.0,
        currency = "USD",
        expertNotes = notes,
        quantity = 1
    )

    private fun alcohol(rules: PalateRules, wine: Wine, scope: GateScope) =
        rules.verdicts(wine, scope = scope).first { it.gate == PalateRules.GATE_ALCOHOL }

    @Test
    fun atTheShelfTheCeilingStillStopsIt() {
        val verdict = alcohol(rules, bottle(14.9), GateScope.PURCHASE)
        assertEquals(GateVerdict.FAIL, verdict.verdict)
    }

    @Test
    fun inTheCellarTheSameBottleIsOneToDrink() {
        val verdict = alcohol(rules, bottle(14.9), GateScope.OWNED)
        assertEquals(GateVerdict.CONDITIONAL, verdict.verdict)
        assertTrue(verdict.detail, verdict.detail.contains("already own this bottle"))
        assertTrue("a conditional pass is a pass", verdict.verdict.isPass)
    }

    @Test
    fun anOwnedBottleThatAlsoFailsTheRestIsStillOut() {
        val jammy = bottle(14.9, notes = "jammy and plush, a fruit bomb")
        val gates = rules.verdicts(jammy, scope = GateScope.OWNED)
        assertEquals(
            GateVerdict.FAIL,
            gates.first { it.gate == PalateRules.GATE_ALCOHOL }.verdict
        )
        assertEquals(
            GateVerdict.FAIL,
            gates.first { it.gate == GateId.G4_STYLE.title }.verdict
        )
    }

    @Test
    fun anOwnedBottleInsideTheCeilingIsUnaffected() {
        assertEquals(
            GateVerdict.PASS,
            alcohol(rules, bottle(13.5), GateScope.OWNED).verdict
        )
    }
}
