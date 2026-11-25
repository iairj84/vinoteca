package com.wineverdict.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Ticket 2026-11-25: judgement quality, producer spread, strip, glass vs bottle.
 * Fixture uses the three real picks from the 1.0.10 output. Only 50/54/54 and
 * the Trepat/11.5% facts are real menu values; grapes/regions below are the
 * ticket's Table 1 fixture values.
 */
class ListServiceRulesTest {

    private fun pool(): List<ServiceRow> = listOf(
        ServiceRow(
            id = "mp-brut", producer = "Miquel Pons", cuvee = "Cava Brut", vintage = 2021,
            grapes = listOf("Macabeu", "Xarel·lo", "Parellada"),
            region = "Penedès", parentRegion = "Catalonia",
            abvPct = null, glassUsd = 13.0, bottleUsd = 50.0,
            description = "dry, mineral, estate-grown"
        ),
        ServiceRow(
            id = "cdor-rosat", producer = "Castell d'Or", cuvee = "Flama d'Or Brut Rosé",
            vintage = null, grapes = listOf("Trepat"),
            region = "Catalunya", parentRegion = "Catalunya",
            abvPct = 11.5, glassUsd = 14.0, bottleUsd = 54.0,
            description = "bone-dry, savory, strawberry"
        ),
        ServiceRow(
            id = "cdor-brut", producer = "Castell d'Or", cuvee = "Flama Cava Brut",
            vintage = 2022,
            grapes = listOf("Macabeu", "Xarel·lo", "Parellada"),
            region = "Catalunya", parentRegion = "Catalonia",
            abvPct = null, glassUsd = null, bottleUsd = 54.0,
            description = "citrus, fine bubble"
        )
    )

    private fun byId(): Map<String, ServiceRow> = pool().associateBy { it.id }

    @Test
    fun threeRealReasonsAreAllPortable() {
        val rows = pool()
        val byId = byId()
        assertTrue(
            reasonIsPortable(
                "Crisp, traditional method Spanish sparkling wine, perfect for starting a meal.",
                byId["mp-brut"]!!, rows
            )
        )
        assertTrue(
            reasonIsPortable(
                "Unique grape variety offers a distinct, savory alternative to standard sparkling wines.",
                byId["cdor-rosat"]!!, rows
            )
        )
        assertTrue(
            reasonIsPortable(
                "A solid, classic Cava blend that provides consistent, reliable quality for the price.",
                byId["cdor-brut"]!!, rows
            )
        )
    }

    @Test
    fun superlativeIsRejectedForEveryRow() {
        val rows = pool()
        for (r in rows) {
            assertTrue(
                reasonIsPortable(
                    "It is the most accessible and reliable sparkling option on this list.",
                    r, rows
                )
            )
            assertTrue(failsSuperlative("It is the most accessible option on this list."))
        }
    }

    @Test
    fun trepatNamesItsOwnRowOnly() {
        val rows = pool()
        val byId = byId()
        val good = "Trepat rosé at 11.5%, bone-dry strawberry from Castell d'Or."
        assertFalse(reasonIsPortable(good, byId["cdor-rosat"]!!, rows))
        assertTrue(reasonIsPortable(good, byId["cdor-brut"]!!, rows))
    }

    @Test
    fun sharedGrapeIsNotDistinctive() {
        // Parellada grows on mp-brut AND cdor-brut, so naming it proves nothing.
        val rows = pool()
        val byId = byId()
        assertTrue(
            reasonIsPortable("Parellada cava, citrus and fine bubble.", byId["mp-brut"]!!, rows)
        )
    }

    @Test
    fun producerCapDropsRatherThanSubstitutes() {
        val rows = byId()
        val picks = listOf(
            Scored("mp-brut", "a"),
            Scored("cdor-rosat", "b"),
            Scored("cdor-brut", "c")
        )
        val out = enforceProducerCap(picks, rows)
        assertEquals(listOf("mp-brut", "cdor-rosat"), out.map { it.id })
    }

    @Test
    fun producerMatchingIgnoresCaseAndAccents() {
        val rows = mutableMapOf<String, ServiceRow>()
        rows["a"] = ServiceRow(
            "a", "CASTELL D'OR", "X", null, listOf("Trepat"),
            "Catalunya", "Catalunya", 11.5, 14.0, 54.0, "d"
        )
        rows["b"] = ServiceRow(
            "b", "Castell d'Or", "Y", null, listOf("Macabeu"),
            "Catalunya", "Catalunya", null, 13.0, 52.0, "d"
        )
        val out = enforceProducerCap(listOf(Scored("a", "x"), Scored("b", "y")), rows)
        assertEquals(1, out.size)
    }

    @Test
    fun glassAnswerCannotContainBottleOnlyWine() {
        val rows = byId()
        try {
            assertUniformUnit(
                listOf(Scored("cdor-brut", "x")),
                rows, Service.GLASS
            )
            fail("expected throw")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("no glass price"))
        }
        // And the ranked glass answer excludes it entirely.
        val ranked = rankForService(
            listOf(Scored("mp-brut", "a"), Scored("cdor-rosat", "b"), Scored("cdor-brut", "c")),
            rows, Service.GLASS
        )
        assertEquals(listOf("mp-brut", "cdor-rosat"), ranked.map { it.id })
    }

    @Test
    fun noGlassPriceIsNeverInferred() {
        // cdor-brut lists a bottle only: its glass price stays null, never bottle/4.
        val row = byId()["cdor-brut"]!!
        assertNull(row.glassUsd)
        // A menu with no glass prices at all yields no glass answer.
        val bottleOnly = listOf(
            ServiceRow("x", "A", "X", null, listOf("Macabeu"), "Penedès", "Catalonia", null, null, 50.0, "d"),
            ServiceRow("y", "B", "Y", null, listOf("Trepat"), "Catalunya", "Catalunya", 11.5, null, 54.0, "d")
        )
        val ranked = rankForService(
            listOf(Scored("x", "a"), Scored("y", "b")),
            bottleOnly.associateBy { it.id }, Service.GLASS
        )
        assertTrue(ranked.isEmpty())
    }

    @Test
    fun unitChangesTheRanking() {
        val rows = mapOf(
            "cheap-glass" to ServiceRow(
                "cheap-glass", "A", "X", null, listOf("Trepat"), "Penedès", "Catalonia",
                11.5, 15.0, 40.0, "d"
            ),
            "cheap-bottle" to ServiceRow(
                "cheap-bottle", "B", "Y", null, listOf("Macabeu"), "Penedès", "Catalonia",
                12.0, 12.0, 60.0, "d"
            )
        )
        val picks = listOf(Scored("cheap-glass", "a"), Scored("cheap-bottle", "b"))
        val byGlass = rankForService(picks, rows, Service.GLASS).map { it.id }
        val byBottle = rankForService(picks, rows, Service.BOTTLE).map { it.id }
        assertEquals(listOf("cheap-bottle", "cheap-glass"), byGlass)
        assertEquals(listOf("cheap-glass", "cheap-bottle"), byBottle)
    }

    @Test
    fun breakEvenIsIntegerMathOnRealPrices() {
        val rows = byId()
        assertEquals(4, bottlePaysFrom(rows["mp-brut"]!!))
        assertEquals(4, bottlePaysFrom(rows["cdor-rosat"]!!))
        assertNull(bottlePaysFrom(rows["cdor-brut"]!!))
    }

    @Test
    fun stripShowsUnknownAbvAndNeverEstimates() {
        val rows = byId()
        val mp = strip(rows["mp-brut"]!!)
        println("STRIP mp-brut: $mp")
        assertTrue(mp.contains("ABV unknown"))
        assertFalse(mp.contains("11.5"))
        val rosat = strip(rows["cdor-rosat"]!!)
        println("STRIP cdor-rosat: $rosat")
        assertTrue(rosat.contains("ABV 11.5%"))
        assertTrue(rosat.contains("glass \$14.00"))
        assertTrue(rosat.contains("bottle \$54.00"))
        assertTrue(rosat.contains("4th glass"))
        assertTrue(rosat.contains("source: menu"))
    }

    @Test
    fun buttonNamesItsUnit() {
        assertEquals("Choose glass or bottle", orderButtonLabel(null))
        assertEquals("Order a glass", orderButtonLabel(Service.GLASS))
        assertEquals("Order the bottle", orderButtonLabel(Service.BOTTLE))
    }

    @Test
    fun bottleModeKeepsTheModelOrder() {
        // Snapshot: the Nov 25 three, answered in Bottle mode, rank exactly as
        // the model ordered them — the unit fix is additive to scoring.
        val rows = byId()
        val picks = listOf(
            Scored("mp-brut", "a"),
            Scored("cdor-rosat", "b"),
            Scored("cdor-brut", "c")
        )
        val capped = enforceProducerCap(picks, rows)
        val ranked = rankForService(capped, rows, Service.BOTTLE).map { it.id }
        assertEquals(listOf("mp-brut", "cdor-rosat"), ranked)
    }

    @Test
    fun notSureRanksByBottleAndNamesCheapestPour() {
        val rows = pool()
        val line = cheapestPourLine(rows)
        assertTrue(line!!.contains("Miquel Pons"))
        assertTrue(line.contains("\$13.00"))
    }
}
