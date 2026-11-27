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
 * Finds a picture for the bottles that have none.
 *
 * A wine added from a search now keeps the photograph the page declares for it; this walks the
 * bottles already in the cellar and asks the same question about each one. No model is asked, so it
 * costs no quota — only the keyless search and the pages it names — and nothing is written until a
 * picture is actually in hand, so a cellar whose pages publish none is left exactly as it was.
 *
 * One bottle at a time, with a pause between them: the search is a free public endpoint, and a
 * hundred bottles fired at it together is a blocked endpoint rather than a hundred pictures. A
 * page that declares no picture is an answer, not a failure — it is counted, and the bottle stays
 * bare for the camera on its own page.
 */
class PhotoBackfill(
    private val wines: () -> List<Wine>,
    private val find: suspend (Wine) -> ByteArray?,
    private val save: suspend (String, ByteArray) -> Unit,
    private val scope: CoroutineScope,
    private val pauseMillis: Long = 1_200L
) {
    data class Progress(
        val running: Boolean = false,
        val done: Int = 0,
        val total: Int = 0,
        val current: String = "",
        /** Bottles this run gained a picture for. */
        val found: Int = 0,
        /** Lookups that did not complete. Nothing was written, so the next run asks again. */
        val failed: Int = 0,
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
