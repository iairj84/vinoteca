package com.wineverdict.app.ai

import com.wineverdict.app.model.Wine
import com.wineverdict.app.model.WineType

/**
 * Reading a printed wine list with the phone alone.
 *
 * ML Kit already pulls the lines off the page in about a second — on a photographed list it
 * returns the wines and their prices nearly verbatim. Until now that was shown as raw text and
 * thrown away, so a provider that timed out or ran out of room left the user with nothing, even
 * though the names and prices were sitting in the preview.
 *
 * A printed list is regular in a way a label is not: one wine per block, its price on a line of
 * its own, sections marked by a heading word. That is enough to turn the phone's own lines into
 * wines with no network at all. What it cannot do is judge them: no ABV is printed and nothing
 * says how a bottle tastes, so the ranking that comes out is price — which is what `ListPicks`
 * already says honestly when a read separates nothing.
 */
object LocalListRead {

    /** Section headings, and what they say about the wines under them. */
    private val HEADINGS: Map<String, WineType> = mapOf(
        "SPARKLING" to WineType.SPARKLING,
        "SPARKLING WINE" to WineType.SPARKLING,
        "CHAMPAGNE" to WineType.SPARKLING,
        "CAVA" to WineType.SPARKLING,
        "PROSECCO" to WineType.SPARKLING,
        "WHITE" to WineType.WHITE,
        "WHITES" to WineType.WHITE,
        "BLANC" to WineType.WHITE,
        "BLANCO" to WineType.WHITE,
        "RED" to WineType.RED,
        "REDS" to WineType.RED,
        "ROUGE" to WineType.RED,
        "TINTO" to WineType.RED,
        "ROSE" to WineType.ROSE,
        "ROSÉ" to WineType.ROSE,
        "ROSADO" to WineType.ROSE,
        "ORANGE" to WineType.ORANGE,
        "SKINS" to WineType.ORANGE,
        "DESSERT" to WineType.DESSERT,
        "SWEET" to WineType.DESSERT,
        "FORTIFIED" to WineType.FORTIFIED,
        "SHERRY" to WineType.FORTIFIED,
        "PORT" to WineType.FORTIFIED
    )

    /** Headings that mark a section without saying what is in it. */
    private val PLAIN_HEADINGS = setOf("BY THE GLASS", "GLASSES", "GLASS", "BOTTLES", "SELECTION")

    /**
     * Lines that are the restaurant's own furniture rather than a wine: "WINE LIST", "ALL PRICES
     * IN EUROS", "VAT INCLUDED". They never carry a price, so they are dropped before they can
     * join a bottle's name.
     */
    private val NOISE = Regex(
        "(?i)^(wine list|wines by|all prices|prices|vat|service|cover charge|please ask|" +
            "ask your|sommelier|page|menu|restaurant)\\b"
    )

    private val PRICE = Regex(
        "(?i)(?:glass)\\s*[-–—:.]?\\s*([0-9]+(?:[.,][0-9]{1,2})?)" +
            "[^0-9]*?(?:bottle|btl)\\s*[-–—:.]?\\s*([0-9]+(?:[.,][0-9]{1,2})?)|" +
            "(?:bottle|btl)\\s*[-–—:.]?\\s*([0-9]+(?:[.,][0-9]{1,2})?)" +
            "[^0-9]*?(?:glass)\\s*[-–—:.]?\\s*([0-9]+(?:[.,][0-9]{1,2})?)|" +
            "(?:glass)\\s*[-–—:.]?\\s*([0-9]+(?:[.,][0-9]{1,2})?)|" +
            "(?:bottle|btl)\\s*[-–—:.]?\\s*([0-9]+(?:[.,][0-9]{1,2})?)"
    )

    private val YEAR = Regex("\\b(19[5-9][0-9]|20[0-4][0-9])\\b")

    /**
     * The wines the phone can read out of [lines]. Null when the phone did not find a list: fewer
     * than two priced wines is a label, a shelf or a page it could not follow, and a guessed wine
     * is worse than saying so.
     */
    fun wines(lines: List<String>, currency: String): SceneScan? {
        val found = mutableListOf<ScannedLabel>()
        var section: WineType? = null
        val buffer = mutableListOf<String>()
        // The name above the first heading, when the page prints one: it is what makes a menu
        // findable months later, so it is captured rather than thrown away with the headings.
        var venue = ""

        lines.forEach { raw ->
            val line = raw.replace(Regex("\\s+"), " ").trim()
            if (line.length < 2) return@forEach

            val price = priceOf(line)
            if (price != null) {
                if (buffer.isNotEmpty()) {
                    wineFrom(buffer, price.first, price.second, section, currency)?.let { found += it }
                    buffer.clear()
                }
                return@forEach
            }

            val heading = headingOf(line, buffer.isEmpty())
            if (heading != null) {
                // A heading closes whatever came before it: the restaurant's name at the top of
                // the page is exactly this case, and it must not become the first wine's producer.
                // Taken as the venue instead, before the buffer is dropped.
                if (found.isEmpty() && venue.isBlank() && buffer.isNotEmpty()) {
                    venue = buffer.joinToString(" ").trim()
                }
                buffer.clear()
                heading.type?.let { section = it }
                return@forEach
            }

            if (NOISE.containsMatchIn(line)) return@forEach

            buffer += line.trimEnd(',', ';', ' ', '-')
        }

        // A final wine whose price line the phone dropped: keep it, with no price.
        if (buffer.isNotEmpty()) {
            wineFrom(buffer, null, null, section, currency)?.let { found += it }
        }

        val distinct = found
            .filter { it.wine.name.isNotBlank() || it.wine.producer.isNotBlank() }
            .distinctBy { "${it.wine.title}|${it.wine.vintage}" }
            .take(30)
        if (distinct.size < 2) return null

        return SceneScan(scene = Scene.LIST, bottles = distinct, notes = ON_PHONE, venue = venue)
    }

    /** (bottle, glass) — either may be null, and a price with no wine above it is just a price. */
    private fun priceOf(line: String): Pair<Double?, Double?>? {
        val match = PRICE.find(line) ?: return null
        val groups = match.groupValues
        fun at(index: Int): Double? = groups.getOrNull(index)
            ?.takeIf { it.isNotBlank() }
            ?.replace(',', '.')
            ?.toDoubleOrNull()
            ?.takeIf { it > 0.0 && it < 5000.0 }
        // "GLASS - 13 BOTTLE - 50" reads glass first, and the pair is returned bottle-first.
        return when {
            at(1) != null -> at(2) to at(1)
            at(3) != null -> at(3) to at(4)
            at(5) != null -> null to at(5)
            at(6) != null -> at(6) to null
            else -> null
        }
    }

    /**
     * A heading, and the type it declares. Null means "not a heading". A heading with a null type
     * ("BY THE GLASS") still closes the wine above it — it just says nothing about the next one.
     */
    private class Section(val type: WineType?)

    /**
     * [alone] is true when no wine line is waiting to be finished. A section word the table above
     * knows always counts; an all-caps line it does not know only counts when nothing is pending,
     * because "CATALONIA NV" is also two all-caps words and is the second line of the wine above it.
     */
    private fun headingOf(line: String, alone: Boolean): Section? {
        val clean = line.trim().trimEnd(':', '.')
        val upper = clean.uppercase()
        HEADINGS[upper]?.let { return Section(it) }
        if (upper in PLAIN_HEADINGS || NOISE.containsMatchIn(clean)) return Section(null)
        if (!alone) return null

        // A section the table does not know ("CLUB CAVA"): a heading all the same, so it must not
        // become a wine's name. Only an original all-caps line, since a wine always carries mixed
        // case, a quote or a price.
        val words = clean.split(' ').filter { it.isNotBlank() }
        val lettersOnly = words.all { word -> word.all { it.isLetter() || it in "É·&'" } }
        return if (words.size in 1..2 && lettersOnly && clean == upper && clean.length <= 18) {
            Section(null)
        } else {
            null
        }
    }

    private fun wineFrom(
        lines: List<String>,
        bottle: Double?,
        glass: Double?,
        section: WineType?,
        currency: String
    ): ScannedLabel? {
        val joined = lines.joinToString(" , ").replace(Regex("\\s+"), " ").trim().trim(',', ' ', '-')
        if (joined.isBlank()) return null
        val parts = joined.split(',').map { it.trim() }.filter { it.isNotBlank() }
        if (parts.isEmpty()) return null

        val first = parts.first()
        if (first.count { it.isLetter() } < 3) return null

        val vintage = YEAR.find(joined)?.value?.toIntOrNull()
        // The last part is the wine's origin ("CATALONIA NV", "PENEDES 2021"); the ones between it
        // and the name are the grapes the list printed on its description line.
        val tail = parts.drop(1)
        val region = tail.lastOrNull()
            ?.replace(YEAR, "")
            ?.replace(Regex("(?i)\\bnv\\b"), "")
            ?.trim(' ', ',', '-', '.')
            ?.takeIf { it.isNotBlank() && it.length <= 40 }
            .orEmpty()
        val grapes = tail.dropLast(1)
            .flatMap { part -> part.split('/', '·', '&').map { it.trim() } }
            .map { it.replace(YEAR, "").trim(' ', ',', '.', '-') }
            .filter { it.isNotBlank() && it.length in 3..24 && it.any { c -> c.isLetter() } }
            .distinct()
            .take(4)

        val (producer, name) = splitNames(first)
        if (name.isBlank() && producer.isBlank()) return null

        return ScannedLabel(
            wine = Wine(
                producer = producer,
                name = name,
                vintage = vintage,
                type = section ?: typeFrom(name.ifBlank { producer }),
                region = region,
                grapes = grapes,
                price = bottle,
                currency = currency,
                source = "phone-ocr"
            ),
            fitScore = null,
            fitReason = "",
            flavorTags = emptyList(),
            priceEstimate = bottle,
            // Names came off the phone's own reading, unjudged: "medium" is the honest word for a
            // line that is legible but that no model has checked.
            confidence = "medium",
            readNotes = ON_PHONE,
            wineFound = true,
            valueVerdict = "unknown",
            glassPrice = glass
        )
    }

    /**
     * "MIQUEL PONS CAVA BRUT" is a producer and a cuvée with nothing between them but a space, so
     * the split is a guess in the only place a guess is free: the title reads the same either way.
     * A quoted name ("CASTELL D'OR 'FLAMA' ...") marks the boundary exactly, so that is used first.
     */
    private fun splitNames(line: String): Pair<String, String> {
        // A quote that starts a word opens the cuvée; the apostrophe in "CASTELL D'OR" does not.
        val quote = line.indices.firstOrNull { index ->
            (line[index] == '\'' || line[index] == '‘') && index > 0 && line[index - 1] == ' '
        }
        if (quote != null) {
            val producer = line.substring(0, quote).trim().trimEnd(',', '-', ' ')
            val name = line.substring(quote).trim()
            if (producer.isNotBlank() && name.isNotBlank()) return producer to name
        }
        val words = line.split(' ').filter { it.isNotBlank() }
        if (words.size <= 2) return words.joinToString(" ") to ""
        return words.take(2).joinToString(" ") to words.drop(2).joinToString(" ")
    }

    private fun typeFrom(text: String): WineType {
        val upper = text.uppercase()
        return when {
            listOf("CAVA", "BRUT", "CHAMPAGNE", "SPUMANTE", "PROSECCO", "CRÉMANT", "CREMANT", "NATURE")
                .any { upper.contains(it) } -> WineType.SPARKLING
            listOf("ROSADO", "ROSÉ", "ROSE").any { upper.contains(it) } -> WineType.ROSE
            else -> WineType.RED
        }
    }

    const val ON_PHONE = "read on the phone"
}
