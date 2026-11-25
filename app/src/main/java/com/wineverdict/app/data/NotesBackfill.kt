package com.wineverdict.app.data

import com.wineverdict.app.ai.AiClient
import com.wineverdict.app.ai.AiException
import com.wineverdict.app.model.Wine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Works through the cellar a bottle at a time, asking the grounded lookup for published notes and
 * saving each result on the bottle it belongs to.
 *
 * A hundred bottles is a hundred requests, so three things make that practical on a free key:
 * the pace comes from [AiClient]'s limiter (one request per `60 / rpm` seconds, so ten a minute at
 * the default), a bottle that already carries a check stamp is never asked twice, and stopping —
 * Cancel, or the process dying — costs only the bottle in flight. Tapping the button again carries
 * on where it stopped, because the record, not this object, holds the progress.
 */
class NotesBackfill(
    private val ai: AiClient,
    private val cellar: CellarRepository,
    private val scope: CoroutineScope
) {
    data class Progress(
        val running: Boolean = false,
        val done: Int = 0,
        val total: Int = 0,
        val current: String = "",
        val found: Int = 0,
        val missed: Int = 0,
        val message: String? = null
    )

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        val pending = cellar.data.value.wines.filter { it.needsNotes() }
        if (pending.isEmpty()) {
            _progress.value = Progress(message = "Every bottle has been checked already.")
            return
        }
        job = scope.launch {
            var done = 0
            var found = 0
            var missed = 0
            var failures = 0
            var problem: String? = null
            _progress.value = Progress(running = true, total = pending.size)

            for (wine in pending) {
                if (!isActive) return@launch
                _progress.value = Progress(true, done, pending.size, wine.title, found, missed)
                try {
                    val result = ai.sourcedNotes(wine)
                    if (result.notes.isBlank()) {
                        missed++
                        // Stamp the check so a second run does not pay for the same empty answer.
                        cellar.applySourcedNotes(wine.id, "", emptyList(), "")
                    } else {
                        found++
                        cellar.applySourcedNotes(
                            id = wine.id,
                            notes = result.notes,
                            tags = result.tags,
                            sources = result.sources.joinToString(" "),
                            alcohol = result.alcohol
                        )
                    }
                    failures = 0
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // One bad bottle is worth skipping; a bad key, a model without grounding or no
                    // network fails on every bottle, so three in a row ends the run instead of
                    // spending the rest of the queue on the same error.
                    failures++
                    problem = e.message ?: "The lookup failed."
                    // A rejected key, an empty account or nothing configured fails on every bottle:
                    // stop at the first one. Anything else gets three tries before giving up.
                    if (e is AiException && e.fatal) break
                    if (failures >= 3) break
                    continue
                }
                done++
                _progress.value = Progress(true, done, pending.size, wine.title, found, missed)
            }

            val summary = buildString {
                append("Checked $done of ${pending.size}: $found with published notes, $missed with nothing found.")
                if (done < pending.size) {
                    append(" Stopped early; tap again to carry on from here.")
                }
                problem?.let { append("\n").append(it) }
            }
            _progress.value = Progress(
                running = false,
                done = done,
                total = pending.size,
                found = found,
                missed = missed,
                message = summary
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

    private fun Wine.needsNotes(): Boolean =
        notesCheckedAt == 0L && (name.isNotBlank() || producer.isNotBlank())
}