package com.wineverdict.app.data

import com.wineverdict.app.ai.ScannedLabel

/**
 * D1–D5 rules for the list answer (ticket 2026-11-25).
 *
 * This file is additive: [ListIdentity], the `topIds` path and
 * `ScanParsing.applyJudgement` are untouched. Everything here runs after the
 * ranking, on ids and parsed menu numbers only. Glass availability is never
 * inferred — it comes from [ScannedLabel.glassPrice] alone.
 */
enum class Service { GLASS, BOTTLE, UNSURE }

/** A row with the fields the card rules need. Built from the parsed menu, never estimated. */
data class ServiceRow(
    val id: String,
    val producer: String,
    val cuvee: String?,
    val vintage: Int?,
    val grapes: List<String>,
    val region: String,
    val parentRegion: String,
    val abvPct: Double?,
    val glassUsd: Double?,
    val bottleUsd: Double?,
    val description: String,
    val source: String = "menu"
)

fun ScannedLabel.toServiceRow(): ServiceRow {
    val wine = this.wine
    val specific = wine.appellation.trim().ifBlank { wine.region.trim() }
    val parent = when {
        wine.region.trim().isNotBlank() && wine.region.trim() != specific -> wine.region.trim()
        wine.country.trim().isNotBlank() -> wine.country.trim()
        else -> specific
    }
    return ServiceRow(
        id = wine.id,
        producer = wine.producer,
        cuvee = wine.name.ifBlank { null },
        vintage = wine.vintage,
        grapes = wine.grapes.toList(),
        region = specific.ifBlank { wine.region.trim().ifBlank { wine.country.trim() } },
        parentRegion = parent,
        abvPct = wine.alcohol,
        glassUsd = this.glassPrice,
        bottleUsd = wine.price,
        description = wine.expertNotes,
        source = "menu"
    )
}

private fun norm(s: String): String =
    java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
        .replace(Regex("[\\u0300-\\u036f]"), "")
        .lowercase()

private val TOKEN = Regex("[a-z0-9]{4,}")
private fun tokens(s: String): Set<String> = TOKEN.findAll(norm(s)).map { it.value }.toSet()

private fun factsOf(r: ServiceRow): String = buildList {
    add(r.producer); add(r.cuvee ?: "")
    addAll(r.grapes); add(r.region)
    if (r.vintage != null) add(r.vintage.toString())
    if (r.abvPct != null) add(r.abvPct.toString())
}.joinToString(" ")

/** Tokens unique to [row] inside [pool]. Shared grapes/regions are not distinctive. */
fun distinctiveTokens(row: ServiceRow, pool: List<ServiceRow>): Set<String> {
    val mine = tokens(factsOf(row))
    val others = mutableSetOf<String>()
    for (r in pool) {
        if (r.id == row.id) continue
        others += tokens(factsOf(r))
    }
    return mine - others
}

private val BANNED_GENERIC = listOf(
    "perfect for starting a meal", "classic blend", "quality for the price",
    "reliable quality", "great value", "easy drinking", "versatile"
)

private val SUPERLATIVE = Regex(
    "\\b(most|least|best|worst|cheapest|priciest|finest|top)\\b" +
        "|\\bunlike\\s+(the|any)\\s+(other|heavier|broader)\\b" +
        "|\\bon\\s+this\\s+list\\b" +
        "|\\bavoid(ing)?\\b.{0,20}\\b(cloying|sweetness)\\b",
    RegexOption.IGNORE_CASE
)

/** True when the reason would still read true on a neighbouring pick. */
fun reasonIsPortable(reason: String, row: ServiceRow, pool: List<ServiceRow>): Boolean {
    if (SUPERLATIVE.containsMatchIn(reason)) return true
    if (BANNED_GENERIC.any { norm(reason).contains(it) }) return true
    val own = distinctiveTokens(row, pool)
    val lower = norm(reason)
    if (own.none { lower.contains(it) }) return true
    for (other in pool) {
        if (other.id == row.id) continue
        for (t in distinctiveTokens(other, pool)) {
            if (lower.contains(t)) return true
        }
    }
    return false
}

fun failsSuperlative(text: String): Boolean = SUPERLATIVE.containsMatchIn(text)

/** At most one wine per producer. Drops, never substitutes. */
fun enforceProducerCap(picks: List<Scored>, rows: Map<String, ServiceRow>): List<Scored> {
    val seen = mutableSetOf<String>()
    val out = mutableListOf<Scored>()
    for (p in picks) {
        val r = rows[p.id] ?: continue
        val key = norm(r.producer).replace(Regex("[^a-z0-9]"), "")
        if (key in seen) continue
        seen += key
        out += p
    }
    return out
}

fun priceIn(service: Service, r: ServiceRow): Double? = when (service) {
    Service.GLASS -> r.glassUsd
    Service.BOTTLE -> r.bottleUsd
    Service.UNSURE -> r.bottleUsd
}

/** Effective service for ranking: null (no choice yet) ranks by bottle, like UNSURE. */
fun effectiveService(service: Service?): Service =
    when (service) {
        Service.GLASS -> Service.GLASS
        else -> Service.BOTTLE
    }

fun assertUniformUnit(picks: List<Scored>, rows: Map<String, ServiceRow>, service: Service) {
    for (p in picks) {
        val r = rows[p.id] ?: throw IllegalStateException("no row for ${p.id}")
        if (priceIn(service, r) == null) {
            throw IllegalStateException("${p.id} has no ${service.name.lowercase()} price")
        }
    }
}

/** Integer break-even in glasses; null unless both prices are real menu numbers. */
fun bottlePaysFrom(r: ServiceRow): Int? {
    val g = r.glassUsd
    val b = r.bottleUsd
    if (g == null || b == null || g <= 0.0) return null
    return kotlin.math.ceil(b / g).toInt()
}

fun serviceOrdinal(n: Int): String {
    val suffix = when {
        n % 100 in 11..13 -> "th"
        n % 10 == 1 -> "st"
        n % 10 == 2 -> "nd"
        n % 10 == 3 -> "rd"
        else -> "th"
    }
    return "$n$suffix"
}

fun money(v: Double): String = String.format(java.util.Locale.US, "$%.2f", v)

fun strip(r: ServiceRow): String {
    val parts = mutableListOf<String>()
    parts += if (r.abvPct != null) "ABV ${r.abvPct}%" else "ABV unknown — check the label"
    r.glassUsd?.let { parts += "glass ${money(it)}" }
    r.bottleUsd?.let { parts += "bottle ${money(it)}" }
    bottlePaysFrom(r)?.let { parts += "bottle pays from the ${serviceOrdinal(it)} glass" }
    if (r.region.isNotBlank()) parts += r.region
    parts += "source: ${r.source}"
    return parts.joinToString(" · ")
}

/** Facts-only fallback. Never invented prose. */
fun factsFallback(r: ServiceRow, service: Service): String {
    val bits = mutableListOf<String>()
    val grape = r.grapes.firstOrNull { it.isNotBlank() } ?: r.cuvee?.takeIf { it.isNotBlank() }
    if (grape != null) bits += grape.trim()
    if (r.abvPct != null) bits += "${r.abvPct}%"
    if (r.vintage != null) bits += r.vintage.toString()
    if (r.region.isNotBlank()) bits += r.region
    priceIn(service, r)?.let { bits += "${money(it)} ${service.name.lowercase()}" }
    return bits.joinToString(" · ").take(160)
}

/** Validate description+reason; on failure return the facts-only line. */
fun validatedText(text: String, row: ServiceRow, pool: List<ServiceRow>, service: Service): String {
    if (text.isBlank()) return factsFallback(row, service)
    if (failsSuperlative(text)) return factsFallback(row, service)
    if (reasonIsPortable(text, row, pool)) return factsFallback(row, service)
    return text
}

/**
 * Re-rank for the unit that will actually be paid: filter to rows with that
 * price, then sort by it (known ABV first on ties, then model order).
 * UNSURE/null ranks by bottle.
 */
fun rankForService(
    picksInModelOrder: List<Scored>,
    rows: Map<String, ServiceRow>,
    service: Service?
): List<Scored> {
    val eff = effectiveService(service)
    if (eff == Service.GLASS && rows.values.none { it.glassUsd != null }) return emptyList()
    val eligible = picksInModelOrder.filter { priceIn(eff, rows[it.id] ?: return@filter false) != null }
    val orderIndex = picksInModelOrder.mapIndexed { i, s -> s.id to i }.toMap()
    return eligible.sortedWith(
        compareBy<Scored> { priceIn(eff, rows[it.id]!!)!! }
            .thenBy { if (rows[it.id]!!.abvPct != null) 0 else 1 }
            .thenBy { orderIndex[it.id] ?: Int.MAX_VALUE }
    )
}

fun orderButtonLabel(service: Service?): String = when (service) {
    Service.GLASS -> "Order a glass"
    Service.BOTTLE -> "Order the bottle"
    else -> "Choose glass or bottle"
}

fun unitLine(service: Service?): String = when (service) {
    Service.GLASS -> "Answering for: glass."
    Service.BOTTLE -> "Answering for: bottle."
    Service.UNSURE -> "Answering for: not sure — both prices shown, ranked by bottle."
    null -> "Choose glass or bottle to answer for."
}

fun cheapestPourLine(pool: List<ServiceRow>): String? {
    val poured = pool.filter { it.glassUsd != null }
    if (poured.isEmpty()) return null
    val best = poured.minByOrNull { it.glassUsd!! } ?: return null
    return "if you're having one glass, the cheapest pour is ${best.producer} at ${money(best.glassUsd!!)}"
}
