package com.wineverdict.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Kotlin port of the ticket's TypeScript fixture, built from the three real
 * cards the Nov 25 build rendered. Only 50/54/54 are real prices; the rest
 * are fixture values, not claims about that menu.
 */
class ListIdentityTest {

    private fun catalog(): List<CatalogRow> = listOf(
        CatalogRow(
            id = "mp-brut", producer = "Miquel Pons", cuvee = "Cava Brut", vintage = 2021,
            region = "Penedès", category = ListCategory.SPARKLING, priceUsd = 50.0,
            description = "Penedès sparkling, estate-grown, Macabeu/Xarel·lo/Parellada, dry and mineral-focused"
        ),
        CatalogRow(
            id = "nav-dama", producer = "Bodegas Naveran", cuvee = "Dama Brut", vintage = 2021,
            region = "Penedès", category = ListCategory.SPARKLING, priceUsd = 62.0,
            description = "Penedès sparkling, estate-grown, Chardonnay-led, crisp, dry and mineral-focused"
        ),
        CatalogRow(
            id = "cdor-flama-rosat", producer = "Castell d'Or", cuvee = "Flama d'Or Brut Rosé",
            vintage = null, region = "Catalunya", category = ListCategory.SPARKLING,
            priceUsd = 54.0, abvPct = 11.5,
            description = "Trepat rosé cava, strawberry and nectarine, bone-dry"
        ),
        CatalogRow(
            id = "ameztoi-blanca", producer = "Ameztoi", cuvee = "Txakoli Blanco", vintage = 2021,
            region = "Getariako Txakolina", category = ListCategory.WHITE, priceUsd = 46.0,
            description = "Basque white, low alcohol, saline, bone-dry and lightly effervescent"
        ),
        CatalogRow(
            id = "cdor-flama-brut", producer = "Castell d'Or", cuvee = "Flama Cava Brut",
            vintage = 2022, region = "Catalunya", category = ListCategory.SPARKLING, priceUsd = 54.0,
            description = "Cava brut, citrus, fine bubble, dry finish"
        ),
        CatalogRow(
            id = "columna-albarino", producer = "Columna", cuvee = "Albariño", vintage = 2022,
            region = "Rías Baixas", category = ListCategory.WHITE, priceUsd = 48.0,
            description = "Rías Baixas white, zesty, saline, citrus-driven, high-acid, dry"
        )
    )

    // Exact detail text from the Nov 25 build. Evidence — do not tidy these strings.
    private val renderedDetails = listOf(
        "2021 'DAMA' BRUT CHARDONNAY · BODEGAS NAVERAN — Penedès sparkling, estate-grown, crisp Chardonnay, dry and mineral-focus",
        "2021 BLANCO · AMEZTOI TXAKOLÍ — Getariako, Basque white, low alcohol, saline, bone-dry and naturally effervescent",
        "2022 COLUMNA ALBARIÑO — Rías Baixas white, zesty, saline, citrus-driven, high-acid, dry and refreshing"
    )
    private val renderedIds = listOf("mp-brut", "cdor-flama-rosat", "cdor-flama-brut")

    @Test
    fun rejectsEachOfTheThreeNov25Cards() {
        val rows = catalog()
        val byId = rows.associateBy { it.id }
        renderedIds.forEachIndexed { i, id ->
            val row = byId[id]!!
            val card = ListIdentity.buildCard(row, "why").copy(detail = renderedDetails[i])
            try {
                ListIdentity.assertIdentity(card, row, rows)
                fail("expected IdentityMismatch for $id")
            } catch (e: IdentityMismatch) {
                assertTrue(e.message!!.contains("detail names"))
            }
        }
    }

    @Test
    fun rejectsTheSameCardsWhenTitleIsAlsoMisplaced() {
        val rows = catalog()
        val byId = rows.associateBy { it.id }
        val titles = listOf(
            "CAVA BRUT · MIQUEL PONS — USD 50.00",
            "'FLAMA' TREPAT ROSADO · CASTELL D'OR — USD 54.00",
            "'FLAMA' CAVA BRUT · CASTELL D'OR — USD 54.00"
        )
        val prices = listOf(50.0, 54.0, 54.0)
        renderedIds.forEachIndexed { i, id ->
            val card = RecCard(
                rowId = id, title = titles[i], detail = renderedDetails[i],
                reason = "", priceUsd = prices[i]
            )
            try {
                ListIdentity.assertIdentity(card, byId[id], rows)
                fail("expected IdentityMismatch for $id")
            } catch (e: IdentityMismatch) {
                assertTrue(e.rowId == id)
            }
        }
    }

    @Test
    fun buildsEachCardFromItsOwnRowOnly() {
        val rows = catalog()
        for (row in rows) {
            val card = ListIdentity.buildCard(row, "why")
            assertTrue(card.detail.contains(row.description))
            assertTrue(card.title.startsWith(ListIdentity.headOf(row)))
            ListIdentity.assertIdentity(card, row, rows)
        }
    }

    @Test
    fun rendersIdenticallyUnder50RandomOrderings() {
        val rows = catalog()
        val scored = rows.map { Scored(it.id, "why") }
        val base = ListIdentity.renderCards(scored, rows, log = {}).cards.associateBy { it.rowId }
        repeat(50) {
            val shuffled = scored.shuffled()
            val (cards, dropped) = ListIdentity.renderCards(shuffled, rows, log = {})
            assertTrue(dropped.isEmpty())
            for (c in cards) assertEquals(base[c.rowId], c)
        }
    }

    @Test
    fun dropsUnknownIdInsteadOfBorrowingNeighbour() {
        val rows = catalog()
        val (cards, dropped) = ListIdentity.renderCards(
            listOf(Scored("mp-brut", "why"), Scored("gone-123", "why")),
            rows, log = {}
        )
        assertEquals(1, cards.size)
        assertEquals("mp-brut", cards[0].rowId)
        assertEquals(listOf("gone-123: not in catalog"), dropped)
    }

    @Test
    fun rejectsInternallyConsistentRowNamingAnotherProducer() {
        val rows = catalog()
        val base = rows.associateBy { it.id }["mp-brut"]!!
        val corrupt = base.copy(
            description = "Penedès sparkling, estate-grown, crisp Chardonnay, dry and mineral-focus · BODEGAS NAVERAN"
        )
        val card = ListIdentity.buildCard(corrupt, "why")
        try {
            ListIdentity.assertIdentity(card, corrupt, rows + corrupt)
            fail("expected IdentityMismatch")
        } catch (e: IdentityMismatch) {
            assertTrue(e.message!!.contains("Bodegas Naveran"))
        }
    }

    @Test
    fun rejectsSameProducerSwapViaSiblingCuvee() {
        // Blind spot: both rows are Castell d'Or, so the producer check skips. The
        // brut row's description carries CAVA, distinctive against the rosat cuvee.
        val rows = catalog()
        val byId = rows.associateBy { it.id }
        val rosat = byId["cdor-flama-rosat"]!!
        val brut = byId["cdor-flama-brut"]!!
        val card = ListIdentity.buildCard(rosat, "why").copy(detail = brut.description)
        try {
            ListIdentity.assertIdentity(card, rosat, rows)
            fail("expected IdentityMismatch for same-producer swap")
        } catch (e: IdentityMismatch) {
            assertTrue(e.message!!.contains("cdor-flama-rosat"))
        }
    }

    @Test
    fun nov25SlotsRenderFromOwnRowsAfterRepair() {
        // Post-fix picture for the Nov 25 payload: the import repair blanks the
        // foreign `what`, so each stored row keeps its own description and every
        // rendered card is built from its own row only. This prints the cards.
        val rows = catalog()
        val rendered = ListIdentity.renderCards(
            listOf(
                Scored("mp-brut", "estate-grown Penedes at half the Naveran price"),
                Scored("cdor-flama-rosat", "the only Trepat rose here"),
                Scored("cdor-flama-brut", "citrus and fine bubble, dry finish")
            ),
            rows, log = {}
        )
        assertTrue(rendered.dropped.isEmpty())
        assertEquals(3, rendered.cards.size)
        for (c in rendered.cards) {
            println("CARD " + c.rowId + " | " + c.title + " || " + c.detail + " // " + c.reason)
            val row = rows.associateBy { it.id }[c.rowId]!!
            ListIdentity.assertIdentity(c, row, rows)
        }
    }

    @Test
    fun catchesMismatchedPrice() {
        val rows = catalog()
        val row = rows.associateBy { it.id }["mp-brut"]!!
        val card = ListIdentity.buildCard(row, "why").copy(priceUsd = 51.0)
        try {
            ListIdentity.assertIdentity(card, row, rows)
            fail("expected IdentityMismatch")
        } catch (e: IdentityMismatch) {
            assertTrue(e.message!!.contains("price 51.0 != catalog 50.0"))
        }
    }
}
