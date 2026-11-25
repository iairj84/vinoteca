package com.wineverdict.app.data

import com.wineverdict.app.model.Wine
import com.wineverdict.app.model.WineType
import com.wineverdict.app.model.formatMoney

/**
 * Identity binding for the "Worth ordering, best first" cards.
 *
 * Diagnosis for the Nov 25 misbinding (written here so the fix location is
 * on the record): (b) import bug, not (a) renderer drift.
 *
 * The renderer never held parallel name/price/description lists: the READ
 * branch built every line from one [com.wineverdict.app.ai.ScannedLabel]
 * object (`bottle.wine.title`, `bottle.wine.price`, `bottle.wine.expertNotes`,
 * `bottle.fitReason` from the same `bottle`). There was no sort, filter or
 * dedupe between the title and its detail, so there was nothing to drift.
 *
 * The rows themselves arrived merged. The judgement attaches the model's
 * `what`/`why` to the phone's rows by menu number
 * (`ScanParsing.applyJudgement`), and the model echoes wine titles inside
 * `what` ("2021 'DAMA' ... · BODEGAS NAVERAN — ..."). When a `what` lands on
 * the wrong row — wrong number back, or a guess about the neighbour — the
 * stored row holds one wine's name and price next to another wine's
 * description, and the renderer faithfully prints the corrupt row. The three
 * Nov 25 cards each named a foreign producer inside their own detail line
 * (Naveran inside a Miquel Pons card, Ameztoi inside a Castell d'Or card,
 * Columna inside the other Castell d'Or card), which a same-row assembly
 * could never produce on its own.
 *
 * So the structural fix is in two places: the importer refuses to attach a
 * `what` that names a different wine on the page (see `applyJudgement`), and
 * this object makes a corrupt row unrenderable — one card from one row, built
 * only here, checked before it reaches the screen, dropped with its rowId
 * when it fails. Ranking is untouched: same wines, same order, same reasons.
 * Only the binding changed.
 */
enum class ListCategory {
    SPARKLING, WHITE, RED, ROSE, SWEET, FORTIFIED;

    companion object {
        fun from(type: WineType): ListCategory = when (type) {
            WineType.SPARKLING -> SPARKLING
            WineType.WHITE -> WHITE
            WineType.RED -> RED
            WineType.ROSE -> ROSE
            WineType.DESSERT -> SWEET
            WineType.FORTIFIED -> FORTIFIED
            WineType.ORANGE -> WHITE
        }
    }
}

/** One wine on the list as judged. One row = one wine; never assembled from parts. */
data class CatalogRow(
    val id: String,
    val producer: String,
    val cuvee: String?,
    val vintage: Int?,
    val region: String,
    val category: ListCategory,
    val priceUsd: Double?,
    val currency: String = "USD",
    val abvPct: Double? = null,
    val description: String
)

/** The only thing the recommendation section may render. */
data class RecCard(
    val rowId: String,
    val title: String,
    val detail: String,
    val reason: String,
    val priceUsd: Double?
)

class IdentityMismatch(message: String, val rowId: String) : IllegalStateException(message)

/** A scored id from the shortlist. The shortlist carries ids only, never indexes. */
data class Scored(val id: String, val reason: String)

data class Rendered(val cards: List<RecCard>, val dropped: List<String>)

fun Wine.toCatalogRow(): CatalogRow = CatalogRow(
    id = id,
    producer = producer,
    cuvee = name.ifBlank { null },
    vintage = vintage,
    region = listOf(appellation, region, country).map { it.trim() }
        .filter { it.isNotBlank() }.distinct().joinToString(", "),
    category = ListCategory.from(type),
    priceUsd = price,
    currency = currency.ifBlank { "USD" },
    abvPct = alcohol,
    description = expertNotes
)

object ListIdentity {

    /** The row's own headline, built the same way [Wine.title] builds it. */
    fun headOf(row: CatalogRow): String {
        val base = listOf(row.cuvee.orEmpty(), row.producer)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        val year = row.vintage?.toString()
        return when {
            year != null && base.isNotBlank() -> "$year $base"
            year != null -> year
            base.isNotBlank() -> base
            else -> "Unnamed wine"
        }
    }

    /** The ONLY way a recommendation card may be created. */
    fun buildCard(row: CatalogRow, reason: String): RecCard {
        val title = buildString {
            append(headOf(row))
            if (row.priceUsd != null) {
                append(" — ").append(formatMoney(row.priceUsd, row.currency))
            }
        }
        return RecCard(
            rowId = row.id,
            title = title,
            detail = row.description,
            reason = reason,
            priceUsd = row.priceUsd
        )
    }

    private fun prefixSibling(a: String, b: String): Boolean {
        val x = a.lowercase()
        val y = b.lowercase()
        return x.startsWith(y) || y.startsWith(x)
    }

    private fun namesProducer(text: String, producer: String): Boolean {
        val clean = producer.trim()
        if (clean.length < 3) return false
        return Regex("\\b${Regex.escape(clean)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)
    }

    /** Normalized words (A-Z plus accented caps) of a cuvee, dropping short glue words. */
    private fun cuveeWords(s: String?): List<String> =
        s.orEmpty().uppercase().split(Regex("[^À-ÞA-Z]+"))
            .filter { it.length >= 3 }

    /** Two-word phrases of [other] that [own] does not share. Single generic words like
     *  CAVA match legitimate descriptions, so only a shared phrase borrows an identity. */
    private fun siblingPhrases(other: String?, own: String?): List<String> {
        fun bigrams(words: List<String>): Set<String> =
            words.zipWithNext { a, b -> " ".let { a + it + b } }.toSet()
        return (bigrams(cuveeWords(other)) - bigrams(cuveeWords(own))).toList()
    }

    private fun namesPhrase(text: String, phrase: String): Boolean {
        if (phrase.length < 8) return false
        val norm = text.uppercase().replace(Regex("[^À-ÞA-Z]+"), " ")
        return norm.contains(phrase)
    }

    /**
     * Hard checks throw [IdentityMismatch]; a leading-year mismatch only warns,
     * because descriptions legitimately contain other years ("founded 1901").
     */
    fun assertIdentity(
        card: RecCard,
        row: CatalogRow?,
        catalog: List<CatalogRow>,
        warn: (String) -> Unit = {}
    ) {
        if (row == null) throw IdentityMismatch("no catalog row for \"${card.rowId}\"", card.rowId)
        if (card.rowId != row.id) throw IdentityMismatch("rowId ${card.rowId} != ${row.id}", card.rowId)
        if (card.priceUsd != row.priceUsd) {
            throw IdentityMismatch("price ${card.priceUsd} != catalog ${row.priceUsd}", card.rowId)
        }
        if (!card.title.startsWith(headOf(row))) {
            throw IdentityMismatch("title does not start with \"${headOf(row)}\"", card.rowId)
        }
        // Foreign-producer check first: the Nov 25 evidence is a detail that names a
        // different wine on the page, and the test asserts on that message even though
        // the detail also differs from the row's own description.
        val foreign = catalog
            .filter { it.id != row.id && !prefixSibling(it.producer, row.producer) }
            .filter { it.producer.isNotBlank() && namesProducer(card.detail, it.producer) }
        if (foreign.isNotEmpty()) {
            throw IdentityMismatch(
                "detail names ${foreign.map { it.producer }.distinct().joinToString(", ")} but card is ${row.producer}",
                card.rowId
            )
        }

        // Same-producer blind spot: two Castell d'Or rows share a producer, so the check
        // above skips them. A detail that names the sibling's cuvee, or that IS the
        // sibling's description, is still a borrowed description.
        val siblings = catalog.filter { it.id != row.id && prefixSibling(it.producer, row.producer) }
        for (sib in siblings) {
            for (phrase in siblingPhrases(sib.cuvee, row.cuvee)) {
                if (namesPhrase(card.detail, phrase)) {
                    throw IdentityMismatch(
                        "detail names sibling phrase " + phrase + " of ${sib.cuvee} but card is ${row.id}",
                        card.rowId
                    )
                }
            }
            val borrowed = sib.description.trim()
            if (borrowed.length >= 24 && borrowed != row.description.trim() &&
                (card.detail.trim() == borrowed || card.detail.contains(borrowed))) {
                throw IdentityMismatch(
                    "detail matches ${sib.id}'s description but card is ${row.id}",
                    card.rowId
                )
            }
        }
        if (card.detail != row.description) {
            throw IdentityMismatch("detail is not this row's own description", card.rowId)
        }
        val year = Regex("\\b(19|20)\\d{2}\\b").find(card.detail)?.value?.toIntOrNull()
        if (row.vintage != null && year != null && year != row.vintage) {
            warn("[identity] vintage hint $year != ${row.vintage} for ${card.rowId}")
        }
    }

    fun renderCards(
        scored: List<Scored>,
        catalog: List<CatalogRow>,
        log: (String) -> Unit = { println(it) },
        warn: (String) -> Unit = { println(it) }
    ): Rendered {
        val rowById = catalog.associateBy { it.id }
        val cards = mutableListOf<RecCard>()
        val dropped = mutableListOf<String>()
        for (s in scored) {
            val row = rowById[s.id]
            if (row == null) {
                dropped += "${s.id}: not in catalog"
                continue
            }
            try {
                val card = buildCard(row, s.reason)
                assertIdentity(card, row, catalog, warn)
                cards += card
            } catch (e: IdentityMismatch) {
                dropped += "${s.id}: ${e.message}"
            }
        }
        if (dropped.isNotEmpty()) {
            log("[identity] dropped ${dropped.size} card(s):\n${dropped.joinToString("\n")}")
        }
        return Rendered(cards, dropped)
    }
}
