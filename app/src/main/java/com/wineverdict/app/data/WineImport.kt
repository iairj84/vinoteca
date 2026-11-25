package com.wineverdict.app.data

import com.wineverdict.app.model.Wine
import com.wineverdict.app.model.WineType
import com.wineverdict.app.util.Csv
import kotlin.math.roundToInt

/** One importable row: a wine plus the location *name* it lives in (resolved later). */
data class ImportRow(val wine: Wine, val locationName: String = "")

data class ImportPreview(
    val rows: List<ImportRow>,
    val locationNames: List<String>,
    val skipped: Int,
    val notes: List<String>,
    val source: String
) {
    val bottles: Int get() = rows.sumOf { it.wine.quantity }
    val distinctWines: Int get() = rows.size
}

/**
 * Reads the CSV files wine apps hand out. CellarTracker's export has 50-odd columns and
 * Vivino's has a dozen; rather than tie to either, every column is matched by name from
 * [ALIASES], so any spreadsheet with wine-ish headings works. Unknown columns are ignored.
 */
object WineImport {

    private val ALIASES: Map<String, List<String>> = mapOf(
        "producer" to listOf("producer", "winery", "wineproducer", "brand", "maker", "domaine", "estate"),
        "name" to listOf("winename", "wine", "name", "title", "cuvee", "bottling", "winecuvee"),
        "designation" to listOf("designation", "vineyard", "site"),
        "vintage" to listOf("vintage", "year", "millesime"),
        "type" to listOf("winetype", "type", "color", "colour", "winecolor", "style"),
        "country" to listOf("country", "nation"),
        "region" to listOf("region", "area", "subregion", "subarea"),
        "appellation" to listOf("appellation", "appellationcontrolee", "ava", "doc", "docg", "aoc"),
        "grapes" to listOf("grapes", "grape", "varietal", "mastervarietal", "variety", "blend", "grapevariety"),
        "quantity" to listOf("quantity", "qty", "count", "bottles", "totalquantity", "cellarcount", "instock", "inventory"),
        "price" to listOf("purchaseprice", "price", "cost", "priceperbottle", "paid", "unitprice"),
        "value" to listOf("value", "valuation", "marketvalue"),
        "currency" to listOf("purchasepricecurrency", "pricecurrency", "currency"),
        "location" to listOf("location", "cellarlocation", "storage", "storagelocation"),
        "bin" to listOf("bin", "binnumber", "slot", "rack", "position", "shelf"),
        "drinkfrom" to listOf("beginconsume", "drinkfrom", "startdrinking", "drinkstart", "begindrink"),
        "drinkto" to listOf("endconsume", "drinkto", "enddrinking", "drinkend", "enddrink"),
        "notes" to listOf("mynotes", "privatenotes", "notes", "note", "cellarnote", "tastingnotes", "comments"),
        "expert" to listOf("expertnotes", "professionalnotes", "review", "reviews"),
        "rating" to listOf("myrating", "rating", "stars"),
        "barcode" to listOf("barcode", "upc", "ean", "sku"),
        "size" to listOf("bottlesize", "size", "format", "volume")
    )

    /** Press scores, kept as a readable line in the wine's reference notes. */
    private val SCORES = linkedMapOf(
        "WA" to "wa",
        "WS" to "ws",
        "AG" to "ag",
        "JS" to "js",
        "WE" to "we",
        "BH" to "bh",
        "JR" to "jr",
        "CT" to "ct"
    )

    fun fromCsv(
        text: String,
        fallbackCurrency: String,
        sourceHint: String = ""
    ): ImportPreview {
        val rows = Csv.parse(text)
        if (rows.isEmpty()) throw IllegalArgumentException("That file is empty.")

        val headerIndex = rows.indexOfFirst { headerMap(it).size >= 2 }
        if (headerIndex < 0) {
            throw IllegalArgumentException(
                "No header row with wine columns was found. The first line needs headings such as Wine, Producer, Vintage, Quantity."
            )
        }
        val header = rows[headerIndex]
        val columns = headerMap(header)
        if (!columns.containsKey("name") && !columns.containsKey("producer")) {
            throw IllegalArgumentException(
                "That file has no wine name or producer column. Headings found: " +
                    header.take(8).joinToString(", ") { it.take(24) }
            )
        }
        val normalizedHeader = header.map { normalize(it) }
        val noteAliases = ALIASES.getValue("notes")
        val noteColumns = normalizedHeader.mapIndexedNotNull { index, value ->
            if (value in noteAliases) index else null
        }
        val scoreColumns = SCORES.mapNotNull { (label, alias) ->
            val index = normalizedHeader.indexOf(alias)
            if (index >= 0) label to index else null
        }

        val source = when {
            sourceHint.isNotBlank() -> sourceHint
            normalizedHeader.any { it == "iwine" || it.startsWith("cellartracker") } -> "CellarTracker"
            normalizedHeader.any { it == "linktowine" } -> "Vivino"
            else -> "a spreadsheet"
        }

        val out = mutableListOf<ImportRow>()
        val locations = linkedSetOf<String>()
        val notes = mutableListOf<String>()
        var skippedBlank = 0
        var skippedEmpty = 0
        var unknownType = 0
        var otherSizes = 0

        fun cell(row: List<String>, field: String): String {
            val index = columns[field] ?: return ""
            return row.getOrNull(index)?.trim().orEmpty()
        }

        rows.drop(headerIndex + 1).forEach { row ->
            var name = cell(row, "name")
            val designation = cell(row, "designation")
            if (designation.isNotBlank() && !name.contains(designation, ignoreCase = true)) {
                name = listOf(name, designation).filter { it.isNotBlank() }.joinToString(" ")
            }
            val producer = cell(row, "producer")
            if (name.isBlank() && producer.isBlank()) {
                skippedBlank++
                return@forEach
            }
            val quantity = intOrNull(cell(row, "quantity")) ?: 1
            if (quantity <= 0) {
                skippedEmpty++
                return@forEach
            }
            val rawType = cell(row, "type")
            if (rawType.isBlank()) unknownType++
            val currency = cell(row, "currency").uppercase().filter { it.isLetter() }.take(3)
                .ifBlank { fallbackCurrency }

            val importedNotes = noteColumns
                .mapNotNull { index -> row.getOrNull(index)?.trim()?.takeIf { it.isNotBlank() } }
                .distinct()
                .toMutableList()
            val size = cell(row, "size")
            if (size.isNotBlank() && !size.contains("750") && !size.contains("75cl", true)) {
                otherSizes++
                importedNotes += "Bottle size: $size"
            }

            val scores = scoreColumns.mapNotNull { (label, index) ->
                val value = row.getOrNull(index)?.trim().orEmpty().toDoubleOrNull()
                if (value != null && value >= 50) "$label ${value.roundToInt()}" else null
            }
            val expert = buildString {
                if (scores.isNotEmpty()) append("Critic scores from the import — ${scores.joinToString(", ")}.")
                val reference = cell(row, "expert")
                if (reference.isNotBlank()) {
                    if (isNotEmpty()) append(' ')
                    append(reference)
                }
            }

            val location = cell(row, "location")
            if (location.isNotBlank()) locations += location

            out += ImportRow(
                wine = Wine(
                    producer = producer,
                    name = name,
                    vintage = yearOrNull(cell(row, "vintage")),
                    type = WineType.from(rawType),
                    country = cell(row, "country"),
                    region = cell(row, "region"),
                    appellation = cell(row, "appellation"),
                    grapes = splitList(cell(row, "grapes")),
                    barcode = cell(row, "barcode"),
                    quantity = quantity,
                    slot = cell(row, "bin"),
                    price = moneyOrNull(cell(row, "price")) ?: moneyOrNull(cell(row, "value")),
                    currency = currency,
                    drinkFrom = yearOrNull(cell(row, "drinkfrom")),
                    drinkTo = yearOrNull(cell(row, "drinkto")),
                    expertNotes = expert,
                    myNotes = importedNotes.joinToString("\n"),
                    rating = starsOrNull(cell(row, "rating")),
                    source = "import"
                ),
                locationName = location
            )
        }

        if (out.isEmpty()) {
            throw IllegalArgumentException("No bottles were found in that file — every row was blank or had quantity 0.")
        }
        if (skippedBlank > 0) notes += "$skippedBlank rows had no wine name and were skipped."
        if (skippedEmpty > 0) notes += "$skippedEmpty rows had quantity 0 (already drunk) and were skipped."
        if (unknownType > 0) notes += "$unknownType rows had no colour or type, so they are stored as red — easy to fix in the editor."
        if (otherSizes > 0) notes += "$otherSizes rows had a bottle size other than 750ml; it is noted on the wine."

        return ImportPreview(
            rows = out,
            locationNames = locations.toList(),
            skipped = skippedBlank + skippedEmpty,
            notes = notes,
            source = source
        )
    }

    private fun headerMap(header: List<String>): Map<String, Int> {
        val normalized = header.map { normalize(it) }
        val found = mutableMapOf<String, Int>()
        ALIASES.forEach { (field, aliases) ->
            val index = normalized.indexOfFirst { it.isNotEmpty() && aliases.contains(it) }
            if (index >= 0) found[field] = index
        }
        return found
    }

    private fun normalize(raw: String): String =
        raw.lowercase().filter { it.isLetterOrDigit() }

    private fun splitList(raw: String): List<String> =
        raw.split(',', ';', '/', '|')
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.equals("none", true) }
            .distinct()

    private fun intOrNull(raw: String): Int? {
        val cleaned = raw.replace(",", "").trim()
        return cleaned.toDoubleOrNull()?.toInt()
    }

    private fun moneyOrNull(raw: String): Double? {
        var cleaned = raw.replace(Regex("[^0-9.,-]"), "")
        if (cleaned.isBlank()) return null
        cleaned = when {
            cleaned.contains(',') && cleaned.contains('.') -> cleaned.replace(",", "")
            cleaned.contains(',') -> cleaned.replace(',', '.')
            else -> cleaned
        }
        return cleaned.toDoubleOrNull()?.takeIf { it > 0 }
    }

    /** "2025", "3/1/2025", 2025.0 — and CellarTracker's 1001, which means non-vintage. */
    private fun yearOrNull(raw: String): Int? {
        val value = raw.trim()
        if (value.isBlank() || value.equals("nv", true) || value.equals("n/v", true)) return null
        Regex("(19|20)\\d{2}").find(value)?.groupValues?.firstOrNull()?.let { return it.toInt() }
        val numeric = value.toDoubleOrNull()?.toInt() ?: return null
        return numeric.takeIf { it in 1900..2100 }
    }

    /** Vivino exports 1-5 stars; CellarTracker's Rating column is on the 100-point scale. */
    private fun starsOrNull(raw: String): Int? {
        val value = raw.replace(",", ".").trim().toDoubleOrNull() ?: return null
        return when {
            value >= 1.0 && value <= 5.0 -> value.roundToInt().coerceIn(1, 5)
            value >= 50.0 && value <= 100.0 -> (value / 20.0).roundToInt().coerceIn(1, 5)
            else -> null
        }
    }
}