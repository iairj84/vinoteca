package com.wineverdict.app.data

import com.wineverdict.app.model.Wine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Finds a picture for the bottles that have none, and reconsiders the ones a page provided.
 *
 * A wine added from a search now keeps the photograph the page declares for it; [start] walks the
 * bottles already in the cellar and asks the same question about each one. No model is asked, so it
 * costs no quota — only the keyless search and the pages it names — and nothing is written until a
 * picture is actually in hand, so a cellar whose pages publish none is left exactly as it was.
 *
 * [check] is the other half: the finder guesses from markup, and a guess can be a shop's banner or a
 * review site's wordmark, so a kept picture is measured against [looksLikeTheBottle] and a picture
 * that is not the bottle is looked for again. A picture the finder kept and the gate rejects is
 * dropped when nothing better turns up — an empty tile with a camera is honest, a wordmark is not.
 * A picture the user's own camera made is only ever replaced by one that does look like the bottle,
 * never deleted on the gate's word alone: their photograph of the bottle is a fact, not decoration.
 *
 * One bottle at a time, with a pause between the searches: the search is a free public endpoint, and
 * a hundred bottles fired at it together is a blocked endpoint rather than a hundred pictures. A
 * page that declares no picture is an answer, not a failure — it is counted, and the bottle stays
 * bare for the camera on its own page.
 */
class PhotoBackfill(
    private val wines: () -> List<Wine>,
    private val find: suspend (Wine) -> ByteArray?,
    private val save: suspend (String, ByteArray) -> Unit,
    private val scope: CoroutineScope,
    private val pauseMillis: Long = 1_200L,
    /** The bytes already kept for a bottle, or null when the file is gone. */
    private val stored: suspend (Wine) -> ByteArray? = { null },
    /** Whether those bytes are a picture of the bottle, asked before any search is made. */
    private val looksLikeTheBottle: (ByteArray) -> Boolean = { true },
    /** Whether the kept picture came from a page rather than from the user's own camera. */
    private val fromThePages: (Wine) -> Boolean = { false },
    /** Takes a picture off a bottle, once a page's guess has been shown not to be the bottle. */
    private val drop: suspend (String) -> Unit = {}
) {
    data class Progress(
        val running: Boolean = false,
        val done: Int = 0,
        val total: Int = 0,
        val current: String = "",
        /** Bottles this run gained, or regained, a picture. */
        val found: Int = 0,
        /** Lookups that did not complete. Nothing was written, so the next run asks again. */
        val failed: Int = 0,
        /** In a check: pictures that already showed the bottle. */
        val sound: Int = 0,
        /** In a check: page pictures taken off because nothing better was found. */
        val emptied: Int = 0,
        /** In a check: pictures that look wrong, kept because no better one was found. */
        val left: Int = 0,
        val message: String? = null
    )

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        val pending = pending(wines())
        if (pending.isEmpty()) {
            _progress.value = Progress(message = "Every bottle has a picture.")
            return
        }
        job = scope.launch {
            _progress.value = Progress(running = true, total = pending.size)
            var done = 0
            var found = 0
            var failed = 0
            var errorsInARow = 0
            var problem: String? = null
            for (wine in pending) {
                if (!isActive) break
                // The pause is between bottles, not before the first one: the picture somebody is
                // waiting for arrives as soon as the search answers.
                if (done > 0) delay(pauseMillis)
                _progress.update { it.copy(current = wine.title) }
                val picture = try {
                    find(wine).also { errorsInARow = 0 }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed += 1
                    errorsInARow += 1
                    problem = e.message ?: "The search failed."
                    null
                }
                if (picture != null && picture.isNotEmpty()) {
                    try {
                        save(wine.id, picture)
                        found += 1
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed += 1
                    }
                }
                done += 1
                _progress.update { it.copy(done = done, found = found, failed = failed) }
                // Three searches in a row that could not be made is a connection or a blocked
                // endpoint, not a cellar of pages without photographs. Stop and say so.
                if (errorsInARow >= 3) {
                    problem = "The search stopped answering, so the rest were left alone. " +
                        "Tap again when the connection is back."
                    break
                }
            }
            _progress.value = Progress(
                running = false,
                done = done,
                total = pending.size,
                found = found,
                failed = failed,
                message = buildString {
                    append("Looked at $done of ${pending.size}: $found gained a picture.")
                    if (failed > 0) append(" $failed could not be asked and will be tried again.")
                    if (done < pending.size) append(" Stopped early; tap again to carry on.")
                    problem?.let { append("\n").append(it) }
                }
            )
        }
    }

    /**
     * Looks again at the pictures the cellar already keeps.
     *
     * Every kept picture is measured against [looksLikeTheBottle] — no network for one that passes,
     * which is most of them, so a check of fifty bottles costs only the searches that are actually
     * needed. A picture that fails is looked for again: the replacement replaces it, and a page's
     * picture with no replacement is taken off rather than left looking like a bottle it is not.
     */
    fun check() {
        if (job?.isActive == true) return
        val pictures = wines().filter { it.photo.isNotBlank() }
        if (pictures.isEmpty()) {
            _progress.value = Progress(message = "No picture is kept yet, so there is nothing to check.")
            return
        }
        job = scope.launch {
            _progress.value = Progress(running = true, total = pictures.size)
            var done = 0
            var sound = 0
            var found = 0
            var emptied = 0
            var left = 0
            var failed = 0
            var searched = 0
            var errorsInARow = 0
            var problem: String? = null
            for (wine in pictures) {
                if (!isActive) break
                val bytes = try {
                    stored(wine)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                if (bytes != null && looksLikeTheBottle(bytes)) {
                    sound += 1
                    done += 1
                    _progress.update { it.copy(done = done, sound = sound) }
                    continue
                }
                if (bytes == null) {
                    // The record names a file that is gone: nothing to judge, and the next run of
                    // the fill has nothing to replace either. Counted, not searched.
                    failed += 1
                    done += 1
                    _progress.update { it.copy(done = done, failed = failed) }
                    continue
                }
                if (searched > 0) delay(pauseMillis)
                searched += 1
                _progress.update { it.copy(current = wine.title) }
                val picture = try {
                    find(wine).also { errorsInARow = 0 }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed += 1
                    errorsInARow += 1
                    problem = e.message ?: "The search failed."
                    null
                }
                if (picture != null && picture.isNotEmpty()) {
                    try {
                        save(wine.id, picture)
                        found += 1
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed += 1
                    }
                } else if (fromThePages(wine)) {
                    // The finder put this there and it is not the bottle: the bare tile with a
                    // camera on it says so honestly, where a wordmark pretends to be the label.
                    try {
                        drop(wine.id)
                        emptied += 1
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed += 1
                    }
                } else {
                    // The user's own photograph. It stays: the gate's opinion is not enough to
                    // throw away the picture they took of the bottle in their hand.
                    left += 1
                }
                done += 1
                _progress.update {
                    it.copy(done = done, found = found, failed = failed, emptied = emptied, left = left)
                }
                if (errorsInARow >= 3) {
                    problem = "The search stopped answering, so the rest were left alone. " +
                        "Tap again when the connection is back."
                    break
                }
            }
            _progress.value = Progress(
                running = false,
                done = done,
                total = pictures.size,
                found = found,
                failed = failed,
                sound = sound,
                emptied = emptied,
                left = left,
                message = buildString {
                    append("Checked $done of ${pictures.size}: $sound were the bottle")
                    if (found > 0) append(", $found were found again")
                    append('.')
                    if (emptied > 0) {
                        append(" $emptied came from a page and had nothing better, so they are bare.")
                    }
                    if (left > 0) {
                        append(" $left look unlike a bottle but none were found to replace them, " +
                            "so they are as they were.")
                    }
                    if (failed > 0) append(" $failed could not be asked and will be tried again.")
                    if (done < pictures.size) append(" Stopped early; tap again to carry on.")
                    problem?.let { append("\n").append(it) }
                }
            )
        }
    }

    fun cancel() {
        job?.cancel()
        val current = _progress.value
        _progress.value = current.copy(
            running = false,
            message = "Stopped after ${current.done}. Tap again to carry on from here."
        )
    }

    companion object {
        /**
         * Bottles that have no picture and can be searched for. A record with neither a producer
         * nor a name has nothing to look up, so it is not counted as missing one.
         */
        fun pending(wines: List<Wine>): List<Wine> = wines.filter {
            it.photo.isBlank() && (it.producer.isNotBlank() || it.name.isNotBlank())
        }
    }
}