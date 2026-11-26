package com.wineverdict.app.data

import android.content.Context
import com.wineverdict.app.ai.SourcedNotes
import com.wineverdict.app.model.CellarData
import com.wineverdict.app.model.StorageLocation
import com.wineverdict.app.model.Wine
import com.wineverdict.app.model.WineType
import com.wineverdict.app.model.defaultLocations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate

/**
 * Whole-cellar persistence. A personal cellar is a few hundred bottles at most,
 * so the entire collection is kept in one JSON document and filtered in memory.
 */
class CellarRepository(context: Context) {

    private val file = File(context.filesDir, "cellar.json")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }
    private val mutex = Mutex()
    private val _data = MutableStateFlow(CellarData())
    val data: StateFlow<CellarData> = _data.asStateFlow()

    fun load() {
        if (file.exists()) {
            runCatching { json.decodeFromString<CellarData>(file.readText()) }
                .onSuccess { _data.value = it }
        }
        if (_data.value.locations.isEmpty()) {
            persist(_data.value.copy(locations = defaultLocations()))
        }
    }

    fun wine(id: String?): Wine? = id?.let { key -> _data.value.wines.firstOrNull { it.id == key } }

    private fun persist(next: CellarData) {
        runCatching { file.writeText(json.encodeToString(next)) }
        _data.value = next
    }

    private suspend fun mutate(block: (CellarData) -> CellarData) = withContext(Dispatchers.IO) {
        mutex.withLock { persist(block(_data.value)) }
    }

    suspend fun upsert(wine: Wine) = mutate { current ->
        val exists = current.wines.any { it.id == wine.id }
        val stamped = wine.copy(updatedAt = System.currentTimeMillis())
        val wines = if (exists) {
            current.wines.map { if (it.id == wine.id) stamped else it }
        } else {
            current.wines + stamped
        }
        current.copy(wines = wines)
    }

    suspend fun delete(id: String) = mutate { current ->
        current.copy(wines = current.wines.filterNot { it.id == id })
    }

    suspend fun adjustQuantity(id: String, delta: Int) = mutate { current ->
        current.copy(
            wines = current.wines.map {
                if (it.id == id) {
                    it.copy(
                        quantity = (it.quantity + delta).coerceAtLeast(0),
                        updatedAt = System.currentTimeMillis()
                    )
                } else {
                    it
                }
            }
        )
    }

    suspend fun setRating(id: String, rating: Int?) = mutate { current ->
        current.copy(
            wines = current.wines.map {
                if (it.id == id) it.copy(rating = rating, updatedAt = System.currentTimeMillis()) else it
            }
        )
    }

    suspend fun toggleFavorite(id: String) = mutate { current ->
        current.copy(
            wines = current.wines.map {
                if (it.id == id) it.copy(favorite = !it.favorite, updatedAt = System.currentTimeMillis()) else it
            }
        )
    }

    /**
     * Replaces a guessed reference note with one read from published pages, keeping the pages so
     * the bottle can say where it came from. Blank values leave what is already on the record.
     */
    suspend fun applySourcedNotes(
        id: String,
        notes: String,
        tags: List<String>,
        sources: String,
        alcohol: Double? = null
    ) =
        mutate { current ->
            current.copy(
                wines = current.wines.map {
                    if (it.id == id) {
                        it.copy(
                            expertNotes = notes.trim().ifBlank { it.expertNotes },
                            flavorTags = if (tags.isNotEmpty()) tags else it.flavorTags,
                            // The label does not always show an ABV, so a page that prints one is
                            // the cheapest way to get the alcohol rule a fact to work with. A
                            // figure already on the record always wins.
                            alcohol = it.alcohol ?: alcohol,
                            notesSource = if (notes.isBlank()) it.notesSource else sources.trim(),
                            notesCheckedAt = System.currentTimeMillis(),
                            updatedAt = System.currentTimeMillis()
                        )
                    } else {
                        it
                    }
                }
            )
        }

    /**
     * Saves everything one lookup answered for a bottle: published notes with their pages, the
     * flavour words, the ABV, the drinking window, the place and the grapes.
     *
     * Every check stamp moves together because one request answered every question — that is what
     * makes one pass enough. A null [alcohol] means pages were read that print no figure, not that
     * nobody looked, and the window, place and grape stamps say the same about their facts.
     */
    suspend fun applyDetails(id: String, alcohol: Double?, result: SourcedNotes) = mutate { current ->
        val now = System.currentTimeMillis()
        current.copy(
            wines = current.wines.map {
                if (it.id == id) it.withDetails(result, alcohol, now) else it
            }
        )
    }

    suspend fun addTastingNote(id: String, note: String) = mutate { current ->
        val stamp = LocalDate.now().toString()
        current.copy(
            wines = current.wines.map {
                if (it.id == id) {
                    val appended = if (it.myNotes.isBlank()) {
                        "$stamp — ${note.trim()}"
                    } else {
                        it.myNotes.trimEnd() + "\n" + "$stamp — ${note.trim()}"
                    }
                    it.copy(myNotes = appended, updatedAt = System.currentTimeMillis())
                } else {
                    it
                }
            }
        )
    }

    suspend fun moveTo(id: String, locationId: String?, slot: String) = mutate { current ->
        current.copy(
            wines = current.wines.map {
                if (it.id == id) {
                    it.copy(
                        locationId = locationId,
                        slot = slot,
                        updatedAt = System.currentTimeMillis()
                    )
                } else {
                    it
                }
            }
        )
    }

    suspend fun addLocation(name: String): StorageLocation? {
        val clean = name.trim()
        if (clean.isBlank()) return null
        var created: StorageLocation? = null
        mutate { current ->
            if (current.locations.any { it.name.equals(clean, ignoreCase = true) }) {
                current
            } else {
                val location = StorageLocation(name = clean)
                created = location
                current.copy(locations = current.locations + location)
            }
        }
        return created
    }

    suspend fun renameLocation(id: String, name: String) {
        val clean = name.trim()
        if (clean.isBlank()) return
        mutate { current ->
            current.copy(
                locations = current.locations.map { if (it.id == id) it.copy(name = clean) else it }
            )
        }
    }

    suspend fun deleteLocation(id: String) = mutate { current ->
        current.copy(
            locations = current.locations.filterNot { it.id == id },
            wines = current.wines.map { if (it.locationId == id) it.copy(locationId = null) else it }
        )
    }

    /** Restores a backup over the current cellar. */
    suspend fun replaceAll(next: CellarData) = mutate { current ->
        next.copy(
            locations = next.locations.ifEmpty {
                current.locations.ifEmpty { defaultLocations() }
            }
        )
    }

    /**
     * Adds imported rows to the cellar. A wine already present — same producer, cuvée and
     * vintage — keeps its id and notes, takes the higher bottle count and fills any blanks from
     * the import, so importing the same file twice does no harm.
     */
    suspend fun mergeRows(rows: List<ImportRow>): MergeOutcome {
        var outcome = MergeOutcome(added = 0, updated = 0, locationsAdded = 0, skipped = 0)
        mutate { current ->
            val locations = current.locations.toMutableList()
            val wines = current.wines.toMutableList()
            var added = 0
            var updated = 0
            var locationsAdded = 0
            var skipped = 0

            fun locationIdFor(name: String): String? {
                val clean = name.trim()
                if (clean.isBlank()) return null
                locations.firstOrNull { it.name.equals(clean, ignoreCase = true) }?.let { return it.id }
                val created = StorageLocation(name = clean)
                locations += created
                locationsAdded++
                return created.id
            }

            rows.forEach { row ->
                if (row.wine.quantity <= 0) {
                    skipped++
                    return@forEach
                }
                val incoming = row.wine.copy(locationId = locationIdFor(row.locationName))
                val index = wines.indexOfFirst { sameWine(it, incoming) }
                if (index >= 0) {
                    wines[index] = mergeWine(wines[index], incoming)
                    updated++
                } else {
                    wines += incoming
                    added++
                }
            }
            outcome = MergeOutcome(added, updated, locationsAdded, skipped)
            current.copy(wines = wines, locations = locations)
        }
        return outcome
    }

    /** Puts a backup's bottles alongside the current ones. */
    suspend fun mergeCellar(incoming: CellarData): MergeOutcome {
        val names = incoming.locations.associate { it.id to it.name }
        val rows = incoming.wines.map { wine ->
            ImportRow(
                wine = wine.copy(locationId = null),
                locationName = wine.locationId?.let { names[it] }.orEmpty()
            )
        }
        return mergeRows(rows)
    }

    private fun sameWine(left: Wine, right: Wine): Boolean {
        if (left.name.isBlank() && left.producer.isBlank()) return false
        if (right.name.isBlank() && right.producer.isBlank()) return false
        return key(left) == key(right)
    }

    private fun key(wine: Wine): String =
        listOf(wine.producer, wine.name)
            .joinToString("|") { it.trim().lowercase().replace(Regex("\\s+"), " ") } +
            "|" + (wine.vintage ?: 0)

    private fun mergeWine(existing: Wine, incoming: Wine): Wine = existing.copy(
        quantity = maxOf(existing.quantity, incoming.quantity),
        type = if (existing.type == WineType.RED && incoming.type != WineType.RED) {
            incoming.type
        } else {
            existing.type
        },
        country = existing.country.ifBlank { incoming.country },
        region = existing.region.ifBlank { incoming.region },
        appellation = existing.appellation.ifBlank { incoming.appellation },
        grapes = existing.grapes.ifEmpty { incoming.grapes },
        alcohol = existing.alcohol ?: incoming.alcohol,
        barcode = existing.barcode.ifBlank { incoming.barcode },
        locationId = existing.locationId ?: incoming.locationId,
        slot = existing.slot.ifBlank { incoming.slot },
        price = existing.price ?: incoming.price,
        drinkFrom = existing.drinkFrom ?: incoming.drinkFrom,
        drinkTo = existing.drinkTo ?: incoming.drinkTo,
        expertNotes = existing.expertNotes.ifBlank { incoming.expertNotes },
        flavorTags = existing.flavorTags.ifEmpty { incoming.flavorTags },
        myNotes = mergeNotes(existing.myNotes, incoming.myNotes),
        rating = existing.rating ?: incoming.rating,
        updatedAt = System.currentTimeMillis()
    )

    private fun mergeNotes(existing: String, incoming: String): String = when {
        incoming.isBlank() -> existing
        existing.isBlank() -> incoming
        existing.contains(incoming.trim()) -> existing
        else -> existing.trimEnd() + "\n" + incoming.trim()
    }
}

data class MergeOutcome(
    val added: Int,
    val updated: Int,
    val locationsAdded: Int,
    val skipped: Int
)

/**
 * Writes everything one lookup answered onto a bottle.
 *
 * A fact the record already holds always wins: the label in the user's hand beats a page. The
 * window is taken only when the record has neither end, so the user's own years are never
 * half-replaced by a page's. Every stamp moves, because every question was asked in the same
 * request — a stamp says "looked", not "found", which is what lets a fact the pages did not print
 * be asked again instead of reading as settled for ever.
 */
internal fun Wine.withDetails(result: SourcedNotes, alcohol: Double?, now: Long): Wine {
    val note = result.notes.trim()
    val hasWindow = drinkFrom != null || drinkTo != null
    return copy(
        alcohol = this.alcohol ?: alcohol,
        expertNotes = note.ifBlank { expertNotes },
        flavorTags = if (result.tags.isNotEmpty()) result.tags else flavorTags,
        notesSource = if (note.isBlank()) notesSource else result.sources.joinToString(" ").trim(),
        country = country.ifBlank { result.country },
        region = region.ifBlank { result.region },
        appellation = appellation.ifBlank { result.appellation },
        grapes = if (grapes.isEmpty()) result.grapes else grapes,
        drinkFrom = if (hasWindow) drinkFrom else result.drinkFrom,
        drinkTo = if (hasWindow) drinkTo else result.drinkTo,
        notesCheckedAt = now,
        alcoholCheckedAt = now,
        windowCheckedAt = now,
        placeCheckedAt = now,
        grapesCheckedAt = now,
        updatedAt = now
    )
}