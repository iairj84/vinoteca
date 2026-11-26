package com.wineverdict.app.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * "Why it tastes this way", as the model wrote it.
 *
 * Every claim is meant to state a cause and an effect — cool nights keeping acidity high, old vines
 * giving concentration — so the page explains the bottle instead of listing flavours at it. The
 * confidence is kept per claim rather than averaged, because the useful thing to show is which
 * sentences are solid and which are the model reasoning about a region it has not seen.
 */
@Serializable
data class WineInsight(
    val headline: String = "",
    // The schema names, kept on the way out too: the answer is stored as JSON on the record, and a
    // record that says "why" reads back as empty against a parser looking for
    // "why_it_tastes_this_way". One shape in and out means one parser.
    @SerialName("why_it_tastes_this_way") val why: List<InsightClaim> = emptyList(),
    @SerialName("tasting_profile") val taste: InsightTaste? = null,
    @SerialName("drinking_window") val window: InsightWindow? = null,
    @SerialName("food_pairings") val pairings: List<String> = emptyList(),
    @SerialName("for_you") val forYou: String? = null
) {
    /**
     * The claims worth printing: a high-confidence claim as written, a medium one hedged with
     * "Typically", and a low one dropped rather than shown with a caveat nobody reads.
     */
    fun visibleClaims(): List<String> = why.mapNotNull { claim ->
        val text = claim.claim.trim()
        if (text.isBlank()) return@mapNotNull null
        when (claim.confidence.lowercase()) {
            "high" -> text
            // The claim is left exactly as written: lowercasing its first letter turned the
            // producer "Vara y Pulgar" into "vara y Pulgar" in one live answer.
            "medium" -> if (text.contains("typically", ignoreCase = true)) text else "Typically, $text"
            else -> null
        }
    }
}

@Serializable
data class InsightClaim(
    val claim: String = "",
    /** region, grape, vintage, producer or style — what the claim rests on. */
    val basis: String = "",
    val confidence: String = ""
)

@Serializable
data class InsightTaste(
    val body: String = "",
    val tannin: String = "",
    val acidity: String = "",
    val notes: List<String> = emptyList()
) {
    /** "Medium body · high tannin · medium acidity", blanks dropped. */
    val summary: String
        get() = listOf(
            body.takeIf { it.isNotBlank() }?.let { "$it body" },
            tannin.takeIf { it.isNotBlank() }?.let { "$it tannin" },
            acidity.takeIf { it.isNotBlank() }?.let { "$it acidity" }
        ).filterNotNull().joinToString(" · ")
}

@Serializable
data class InsightWindow(
    val from: Int? = null,
    val to: Int? = null,
    val confidence: String = ""
) {
    val label: String
        get() = when {
            from != null && to != null -> "$from–$to"
            from != null -> "from $from"
            to != null -> "until $to"
            else -> ""
        }
}

object InsightParsing {

    /**
     * Reads the model's JSON into [WineInsight]. Returns null rather than throwing: an insight is a
     * nice-to-have beside a bottle the user already owns, and a failed one should leave the page as
     * it was, not raise an error where the bottle record is.
     */
    fun parse(raw: String?): WineInsight? {
        if (raw.isNullOrBlank()) return null
        val root = runCatching { AiJson.objectFrom(raw) }.getOrNull() ?: return null
        val insight = WineInsight(
            headline = root.str("headline"),
            why = claims(root["why_it_tastes_this_way"]),
            taste = taste(root.objOrNull("tasting_profile")),
            window = window(root.objOrNull("drinking_window")),
            pairings = root.stringList("food_pairings"),
            forYou = root.forYou()
        )
        // A reply with no headline and no reasons is not an insight, whatever else came back.
        return insight.takeIf { it.headline.isNotBlank() || it.why.isNotEmpty() }
    }

    private fun claims(element: Any?): List<InsightClaim> {
        val array = element as? JsonArray ?: return emptyList()
        return array.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val claim = obj.str("claim")
            if (claim.isBlank()) return@mapNotNull null
            InsightClaim(
                claim = claim,
                basis = obj.str("basis").lowercase(),
                confidence = obj.str("confidence").lowercase()
            )
        }
    }

    private fun taste(obj: JsonObject?): InsightTaste? {
        if (obj == null) return null
        val taste = InsightTaste(
            body = obj.str("body").lowercase(),
            tannin = obj.str("tannin").lowercase(),
            acidity = obj.str("acidity").lowercase(),
            notes = obj.stringList("notes")
        )
        return taste.takeIf { it.summary.isNotBlank() || it.notes.isNotEmpty() }
    }

    private fun window(obj: JsonObject?): InsightWindow? {
        if (obj == null) return null
        val from = obj.intOrNull("from")
        val to = obj.intOrNull("to")
        // A window outside a bottle's plausible life is a misread year, not a drink date.
        val plausible = { year: Int? -> year?.takeIf { it in 1950..2100 } }
        val window = InsightWindow(
            from = plausible(from),
            to = plausible(to),
            confidence = obj.str("confidence").lowercase()
        )
        return window.takeIf { it.label.isNotBlank() }
    }

    /** The model writes null, "null" or an empty string for "nothing to link". */
    private fun JsonObject.forYou(): String? {
        if (this["for_you"] is JsonNull) return null
        return str("for_you").takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
    }
}
