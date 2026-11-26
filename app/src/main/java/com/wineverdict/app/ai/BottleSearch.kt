package com.wineverdict.app.ai

import com.wineverdict.app.model.Placeholders
import com.wineverdict.app.model.Wine
import com.wineverdict.app.model.WineType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * One bottling a search named, before any page has been opened for it.
 *
 * A search result is a title and a snippet, so this is an identity and nothing more: producer,
 * cuvée, vintage, colour, place, grapes, and the result it came from. The facts that need a page —
 * the note, the ABV a listing prints, the drinking window — are read only for the bottle the user
 * picks, through the ordinary details lookup.
 */
data class BottleCandidate(
    val producer: String,
    val name: String,
    val vintage: Int? = null,
    val type: WineType = WineType.RED,
    val country: String = "",
    val region: String = "",
    val appellation: String = "",
    val grapes: List<String> = emptyList(),
    val alcohol: Double? = null,
    /** The result the identity was named in, when the answer cited one the app actually fetched. */
    val sourceUrl: String = "",
    /** The host of [sourceUrl], for the card: "stollerwine.com". */
    val origin: String = "",
    /** One sentence on what is uncertain — a neighbouring vintage, another bottling. */
    val caveat: String = ""
) {
    /** The wine, as the card names it. */
    val title: String
        get() {
            val base = listOf(name, producer).filter { it.isNotBlank() }.joinToString(" · ")
            return vintage?.let { "$it $base" } ?: base
        }

    val key: String
        get() = listOf(producer, name, vintage?.toString().orEmpty())
            .joinToString("|")
            .lowercase()

    /**
     * The record this candidate stands for. The colour is carried over rather than left to the
     * default, because a rosé added by name must not be stored as a red until someone notices.
     */
    fun toWine(currency: String = "USD"): Wine = Wine(
        producer = producer,
        name = name,
        vintage = vintage,
        type = type,
        country = country,
        region = region,
        appellation = appellation,
        grapes = grapes,
        alcohol = alcohol,
        quantity = 1,
        currency = currency,
        source = "search"
    )
}

/**
 * Finds, and reads, the bottlings a few typed words name.
 *
 * The order matters: the search results are the evidence, so the answer may only name wines those
 * titles and snippets name — no page is opened to help it. Opening a page is the expensive half and
 * is spent only after the user has picked one of the candidates. An answer with no bottles is a
 * real answer: the alternative is a plausible record that no page ever supported.
 */
object BottleSearch {

    /** Enough to recognise the right bottling, few enough to read on a phone. */
    const val MAX = 4

    /** The results as the model reads them: rank, title, URL and the snippet that names the wine. */
    fun evidence(hits: List<WebLookup.Hit>): String = buildString {
        append("SEARCH RESULTS\n")
        hits.take(10).forEach { hit ->
            append("- ").append(hit.title.ifBlank { hit.url }).append(" (").append(hit.url).append(")\n")
            if (hit.snippet.isNotBlank()) append("  ").append(hit.snippet).append("\n")
        }
    }

    fun prompt(query: String): String = """
        You are naming wines from search results. Nothing has been opened yet: all you have is the
        titles and snippets a search engine returned for the words the user typed.

        The user typed: "$query"

        Reply with JSON only, no markdown, no preamble:

        {"bottles":[
          {"producer":"", "name":"", "vintage":"", "type":"", "country":"", "region":"",
           "appellation":"", "grapes":"", "abv":"", "source":"", "caveat":""}
        ]}

        - List the bottlings these results actually name, best match first, at most 4, one entry per
          distinct producer + cuvée + vintage. Never merge two vintages into one entry.
        - A result about a different vintage or a different wine is not a candidate: put it lower or
          leave it out. The vintage is part of the bottling.
        - producer is the winery or estate. name is the cuvée without the producer and without the
          vintage. Do not repeat the vintage inside name.
        - vintage is the four-digit year a result prints, or empty for a non-vintage wine. Never
          guess one, and never carry the year the user typed onto a wine the results do not date.
        - type is Red, White, Rosé, Sparkling, Dessert, Fortified or Orange, from the words in the
          snippet. It is the one fact that cannot be repaired later, so empty is better than a
          guess, but a snippet that says "rosé" or "blanc de blancs" says what it is.
        - country, region, appellation and grapes only when a snippet prints them; empty otherwise.
          Grapes are comma-separated.
        - abv is a percentage only when a snippet prints a figure. Never estimate it.
        - source is one URL copied exactly from the results above — the one that named this bottle.
        - caveat is one short sentence when the vintage or the vineyard is uncertain, otherwise
          empty.
        - If no result names the wine the user asked for, reply {"bottles":[]}. An empty list is a
          real answer; a plausible bottle that no snippet names is not.
    """.trimIndent()

    /**
     * The answer as candidates, with everything the app can check checked here rather than in the
     * prompt: a name that is a placeholder is not a wine, a vintage outside the range a bottle can
     * be is not a vintage, an ABV outside 4–22 is not a figure, and a source the app did not fetch
     * is not a source.
     */
    fun parse(raw: String, known: Collection<String>): List<BottleCandidate> {
        val root = runCatching { AiJson.objectFrom(raw) }
            .getOrElse {
                throw AiException(
                    "The search answer could not be read. Try again, or type the bottle in."
                )
            }
        val items = root["bottles"] as? JsonArray ?: return emptyList()
        val allowed = known.map { it.trim().trimEnd('.', ',') }.toSet()
        return items
            .mapNotNull { (it as? JsonObject)?.let { entry -> read(entry, allowed) } }
            .distinctBy { it.key }
            .take(MAX)
    }

    private fun read(entry: JsonObject, known: Set<String>): BottleCandidate? {
        val producer = Placeholders.stripTrailing(entry.str("producer"))
        val name = Placeholders.stripTrailing(entry.str("name"))
        // A bottle that cannot be named is not a candidate: it would be a record the cellar can
        // never match again. "Type it in" is the honest route for one.
        if (producer.isBlank() || name.isBlank()) return null
        if (Placeholders.isFiller(producer) || Placeholders.isFiller(name)) return null

        val vintage = entry.str("vintage")
            .takeUnless { Placeholders.isFiller(it) }
            ?.let { Regex("(19|20)\\d{2}").find(it)?.value?.toInt() }
            ?.takeIf { it in 1900..2100 }
        val alcohol = entry.doubleOrNull("abv")
            ?.takeIf { it in 4.0..22.0 }
            ?.let { Math.round(it * 10.0) / 10.0 }
        val grapes = entry.stringList("grapes")
            .map { it.trim().trim('.', ',') }
            .filter { it.isNotBlank() && it.length <= 30 && !Placeholders.isFiller(it) }
            .distinct()
            .take(6)
        val url = entry.str("source").trim().trimEnd('.', ',')
        val source = url.takeIf { it in known }.orEmpty()

        return BottleCandidate(
            producer = producer,
            name = name,
            vintage = vintage,
            type = WineType.from(entry.str("type")),
            country = DetailsFacts.place(entry.str("country"), 40),
            region = DetailsFacts.place(entry.str("region"), 60),
            appellation = DetailsFacts.place(entry.str("appellation"), 60),
            grapes = grapes,
            alcohol = alcohol,
            sourceUrl = source,
            origin = host(source),
            caveat = entry.str("caveat").take(160)
        )
    }

    private fun host(url: String): String = runCatching {
        java.net.URI(url).host.orEmpty().removePrefix("www.")
    }.getOrDefault("")
}
