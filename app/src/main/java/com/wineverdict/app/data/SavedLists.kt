package com.wineverdict.app.data

import android.content.Context
import com.wineverdict.app.ai.SceneScan
import com.wineverdict.app.model.Verdict
import com.wineverdict.app.model.Wine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * One wine list as it was read: the wines, the day, and the name the user gave it.
 *
 * The wines are snapshots rather than references on purpose. A list holds wines nobody owns, so
 * there is no cellar record to point at; and a price or a wine on a menu is a fact about that
 * evening, not a field of the cellar. A wine the user keeps becomes a cellar record in its own
 * right, with no bottles, and from then on the verdict lives there — one answer per wine.
 */
@Serializable
data class SavedList(
    val id: String = UUID.randomUUID().toString(),
    /** What the user called it — the restaurant, usually. Blank until they say. */
    val name: String = "",
    /** The wines' identities, so the same page read twice in one evening stays one record. */
    val signature: String = "",
    val savedAt: Long = System.currentTimeMillis(),
    /** The advice answer's own closing note, when it wrote one. */
    val note: String = "",
    /** Ids of the wines the answer picked, in its order. Empty when only a ranking came back. */
    val picks: List<String> = emptyList(),
    val wines: List<Wine> = emptyList()
) {
    /** What the rows and the reminder call it: the name the user gave it, or the day it was read. */
    val title: String
        get() = name.trim().ifBlank {
            "Wine list · " + SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(savedAt))
        }
}

/**
 * Every list that has been read, newest first.
 *
 * Small and hand-written like the chat log: a list is a few hundred bytes of wine, so the whole set
 * lives in one JSON document and the UI filters in memory.
 */
class SavedListStore(context: Context) {

    private val file = File(context.filesDir, "lists.json")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }
    private val _lists = MutableStateFlow<List<SavedList>>(emptyList())
    val lists: StateFlow<List<SavedList>> = _lists.asStateFlow()

    fun load() {
        if (!file.exists()) return
        runCatching { json.decodeFromString<List<SavedList>>(file.readText()) }
            .onSuccess { loaded -> _lists.value = loaded.sortedByDescending { it.savedAt } }
    }

    fun list(id: String?): SavedList? = id?.let { key -> _lists.value.firstOrNull { it.id == key } }

    /**
     * Keeps a page of wines, and returns the record that stands for it.
     *
     * Idempotent for one evening: the same wines read again on the same day return the record
     * already written rather than a second copy. That matters because one page is normally read
     * twice — the phone's own pass, then the model's answer on top — and one page deserves one
     * list. A wine list read again on a later day is a different evening, so it is kept again.
     */
    fun keep(scan: SceneScan): SavedList? {
        val wines = scan.bottles.map { it.wine }.filter { it.producer.isNotBlank() || it.name.isNotBlank() }
        if (wines.isEmpty()) return null
        val signature = signatureOf(wines)
        val today = dayOf(System.currentTimeMillis())
        val picks = scan.advice?.picks
            ?.mapNotNull { pick -> scan.bottles.getOrNull(pick.index)?.wine?.id }
            .orEmpty()
        val note = scan.advice?.note.orEmpty()
        val existing = _lists.value.firstOrNull {
            it.signature == signature && dayOf(it.savedAt) == today
        }
        if (existing != null) {
            // The second read of a page is the one that carries the advice, so a record already
            // written from the first read takes the picks and the note it did not have. Without
            // this the list would keep the wines and forget what the answer picked.
            val merged = existing.copy(
                picks = if (picks.isNotEmpty()) picks else existing.picks,
                note = if (note.isNotBlank()) note else existing.note
            )
            if (merged != existing) {
                write(_lists.value.map { if (it.id == existing.id) merged else it })
            }
            return merged
        }
        val kept = SavedList(signature = signature, note = note, picks = picks, wines = wines)
        write((_lists.value + kept).sortedByDescending { it.savedAt }.take(MAX_LISTS))
        return kept
    }

    fun rename(id: String, name: String) {
        write(_lists.value.map { if (it.id == id) it.copy(name = name.trim()) else it })
    }

    fun delete(id: String) {
        write(_lists.value.filterNot { it.id == id })
    }

    /**
     * Records a verdict on a wine inside a kept list, for a wine that has no cellar record of its
     * own. The note is appended in the same dated shape the bottle page uses, so a list wine's page
     * reads like every other one when it is later kept.
     */
    fun rate(listId: String, wineId: String, rating: Int?, note: String) {
        val stamp = LocalDate.now().toString()
        write(
            _lists.value.map { list ->
                if (list.id != listId) {
                    list
                } else {
                    list.copy(
                        wines = list.wines.map { wine ->
                            if (wine.id != wineId) {
                                wine
                            } else {
                                val said = note.trim()
                                val notes = when {
                                    said.isBlank() -> wine.myNotes
                                    wine.myNotes.isBlank() -> "$stamp — $said"
                                    else -> wine.myNotes.trimEnd() + "\n" + "$stamp — $said"
                                }
                                wine.copy(
                                    rating = rating,
                                    favorite = rating == Verdict.LOVED.rating,
                                    myNotes = notes,
                                    updatedAt = System.currentTimeMillis()
                                )
                            }
                        }
                    )
                }
            }
        )
    }

    private fun write(next: List<SavedList>) {
        _lists.value = next
        runCatching { file.writeText(json.encodeToString(next)) }
    }

    private fun signatureOf(wines: List<Wine>): String =
        wines.map { InsightStore.key(it) }.distinct().sorted().joinToString("|")

    private fun dayOf(at: Long): LocalDate =
        Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate()

    private companion object {
        /** Old lists are worth keeping, but not for ever: this is a notebook, not an archive. */
        const val MAX_LISTS = 30
    }
}
