package com.wineverdict.app.data

import android.content.Context
import com.wineverdict.app.ai.SceneScan
import com.wineverdict.app.model.Verdict
import com.wineverdict.app.model.Wine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    /**
     * Ids of the wines the *user* picked off this page — ordered, noted, or judged afterwards.
     *
     * Kept apart from [picks] on purpose. The answer names three bottles it would order; the user
     * orders one. A card that counted the first as the second said "3 picked that night" about an
     * evening with one glass in it, which is the model's opinion wearing the user's coat.
     */
    val chosen: List<String> = emptyList(),
    val wines: List<Wine> = emptyList(),
    /**
     * The page as photographed, as a file name under the store's photo directory. Blank for a list
     * kept before this existed, and for a photo that could not be written — the wines are still a
     * list without it, which is why this is a name and not a required field.
     */
    val photo: String = ""
) {
    /** What the rows and the reminder call it: the place the page named, the user's name, or the day. */
    val title: String
        get() = name.trim().ifBlank {
            "Wine menu · " + SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(savedAt))
        }
}

/**
 * The wines the user actually had off a menu.
 *
 * Three signals, because the record only starts where the app started watching: the wines marked as
 * ordered, the wines judged on the menu itself, and the wines that exist in the cellar under the
 * same id — which is what the order and the keep actions created, and the only trace left by menus
 * kept before picks were recorded. A suggestion the answer made is not one of them.
 */
fun pickedWines(list: SavedList, cellarIds: Set<String>): List<String> =
    pickedWines(list) { it.id in cellarIds }

/** The same question asked the way the chat has to ask it: by identity, not by a record's id. */
fun pickedWines(list: SavedList, matchesCellar: (Wine) -> Boolean): List<String> =
    (
        list.chosen +
            list.wines.filter { it.rating != null }.map { it.id } +
            list.wines.filter(matchesCellar).map { it.id }
        ).distinct()

/**
 * The menus as the chat reads them, newest first.
 *
 * A menu is the only record of a restaurant: what the page offered, what was ordered there and what
 * was refused. Without it Ask can answer what was drunk but not "what did I order at that place",
 * and it can happily recommend a wine that was turned down on that very menu.
 *
 * Verdicts are read by identity, so a wine judged before it was ever kept under a menu still counts,
 * and the refusal list is stated as a fact rather than a preference: those are wines not to offer.
 */
internal fun menuDigest(
    lists: List<SavedList>,
    cellar: List<Wine>,
    limit: Int = 6,
    now: Long = System.currentTimeMillis()
): String {
    if (lists.isEmpty()) return ""
    val identities = cellar.map { InsightStore.key(it) }.toSet()
    val byIdentity = cellar.associateBy { InsightStore.key(it) }
    return lists.take(limit).joinToString("\n") { list ->
        fun record(wine: Wine): Wine = byIdentity[InsightStore.key(wine)] ?: wine
        // A wine judged Not for me on the menu counts as "handled" for the picked count, which is
        // right on the card and wrong here: the chat is told the wines that were ordered, and told
        // separately which ones were refused.
        val refused = list.wines.filter { Verdict.of(record(it).rating) == Verdict.NOT_FOR_ME }
        val refusedIds = refused.map { it.id }.toSet()
        val picked = pickedWines(list) { InsightStore.key(it) in identities }
            .filterNot { it in refusedIds }
        val pickedNames = list.wines.filter { it.id in picked }.map { wine ->
            val word = Verdict.labelFor(record(wine))
            if (word == null) wine.title else "${wine.title} ($word)"
        }
        buildString {
            append("- ").append(list.title).append(" · ").append(dayWords(list.savedAt, now))
            append(" — ").append(list.wines.size).append(" wines read")
            if (pickedNames.isNotEmpty()) {
                append("; picked ").append(pickedNames.size).append(": ")
                append(pickedNames.take(4).joinToString("; "))
            }
            if (refused.isNotEmpty()) {
                append("; not for me there: ")
                    .append(refused.take(4).joinToString("; ") { it.title })
            }
        }
    }
}

/**
 * Every list that has been read, newest first.
 *
 * Small and hand-written like the chat log: a list is a few hundred bytes of wine, so the whole set
 * lives in one JSON document and the UI filters in memory.
 */
class SavedListStore(private val context: Context) {

    private val file = File(context.filesDir, "lists.json")

    /**
     * The photographs. Kept apart from the JSON because they are pictures, not records: a list is
     * read as wines, but the page is the evidence — small print, sections, the prices the reading
     * may have missed — and the wines can never stand in for it.
     */
    private val photoDir = File(context.filesDir, "list-photos")
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
     * The menus as Ask reads them; blank when none are kept. The cellar is passed in so a verdict
     * given on the bottle — not on the menu's copy — still counts.
     */
    fun digest(cellar: List<Wine>, limit: Int = 6): String =
        menuDigest(_lists.value, cellar, limit)

    /** The photographed page for a list, if this build kept one and the file is still there. */
    fun photoFile(list: SavedList?): File? = list?.photo
        ?.takeIf { it.isNotBlank() }
        ?.let { name -> File(photoDir, name).takeIf { it.isFile } }

    /**
     * Keeps a page of wines, and returns the record that stands for it.
     *
     * Idempotent for one evening: the same wines read again on the same day return the record
     * already written rather than a second copy. That matters because one page is normally read
     * twice — the phone's own pass, then the model's answer on top — and one page deserves one
     * list. A wine list read again on a later day is a different evening, so it is kept again.
     */
    suspend fun keep(scan: SceneScan, page: ByteArray? = null): SavedList? {
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
            // A list kept by a build before the page was stored takes the photo the next read of
            // the same page brings, rather than staying pictureless for ever.
            val withPage = if (merged.photo.isBlank() && page != null) {
                merged.copy(photo = writePage(merged.id, page))
            } else {
                merged
            }
            // The second read of the same page is the one that carries the letterhead, so a menu
            // already kept under its date takes the name the next read found.
            val withPlace = if (withPage.name.isBlank() && scan.venue.isNotBlank()) {
                withPage.copy(name = scan.venue.trim())
            } else {
                withPage
            }
            if (withPlace != existing) {
                write(_lists.value.map { if (it.id == existing.id) withPlace else it })
            }
            return withPlace
        }
        val kept = SavedList(
            // The place, when the page printed one: "Wine list · 26 Nov 2026" is a fallback for a
            // page that named nowhere, not the name every menu should start with.
            name = scan.venue.trim(),
            signature = signature,
            note = note,
            picks = picks,
            wines = wines
        )
        val withPage = if (page != null) kept.copy(photo = writePage(kept.id, page)) else kept
        val next = (_lists.value + withPage).sortedByDescending { it.savedAt }.take(MAX_LISTS)
        write(next)
        // The lists that fell off the end take their photographs with them.
        prunePhotos(next)
        return withPage
    }

    /**
     * Writes the page beside the record. Best effort: a phone with no room for the picture still
     * gets the list, because losing the wines over a failed JPEG would be the wrong trade.
     */
    private suspend fun writePage(id: String, bytes: ByteArray): String = withContext(Dispatchers.IO) {
        runCatching {
            photoDir.mkdirs()
            val name = "$id.jpg"
            File(photoDir, name).writeBytes(bytes)
            name
        }.getOrDefault("")
    }

    private fun prunePhotos(kept: List<SavedList>) {
        val used = kept.map { it.photo }.filter { it.isNotBlank() }.toSet()
        photoDir.listFiles()?.forEach { file ->
            if (file.name !in used) runCatching { file.delete() }
        }
    }

    /** Marks a wine as one the user picked off that page, once, whatever the tap was called. */
    fun choose(listId: String, wineId: String) {
        if (wineId.isBlank()) return
        val list = _lists.value.firstOrNull { it.id == listId } ?: return
        if (wineId in list.chosen) return
        write(
            _lists.value.map {
                if (it.id == listId) it.copy(chosen = it.chosen + wineId) else it
            }
        )
        // The evening has been recorded: the nudge that was booked for this list has nothing left
        // to ask. Guarded by the list's id, so picking from an old menu cannot silence tonight's.
        Reminders.cancelListReview(context, listId)
    }

    fun rename(id: String, name: String) {
        write(_lists.value.map { if (it.id == id) it.copy(name = name.trim()) else it })
    }

    fun delete(id: String) {
        // A reminder to reopen a menu that no longer exists is worse than no reminder.
        Reminders.cancelListReview(context, id)
        val photo = _lists.value.firstOrNull { it.id == id }?.photo.orEmpty()
        write(_lists.value.filterNot { it.id == id })
        if (photo.isNotBlank()) runCatching { File(photoDir, photo).delete() }
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
