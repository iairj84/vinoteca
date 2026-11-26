package com.wineverdict.app.data

import com.wineverdict.app.ai.AiException
import com.wineverdict.app.ai.SourcedNotes
import com.wineverdict.app.model.Wine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Fills in what a bottle is missing — published notes, flavour words and the ABV — in one pass.
 *
 * There used to be two jobs and two buttons, and a bottle needing both went through the queue twice:
 * once for notes and once for the alcohol figure, at about six seconds a bottle, which is five
 * minutes for a cellar of fifty. One lookup already answers both questions (the extraction asks for
 * notes *and* the ABV a page prints), so this walks each incomplete bottle once, and only asks the
 * targeted ABV question when the notes pages carried no figure.
 *
 * Three lookups run at a time. [com.wineverdict.app.ai.AiClient]'s limiter still decides how many
 * requests leave per minute, so the pace is unchanged; what shrinks is the waiting between them.
 *
 * Two rules keep it honest. A figure must be one-decimal and inside [PalateProfile.ALCOHOL_RANGE],
 * because "14" on a page and "40" from a muddled excerpt are not the same claim. And a lookup that
 * does not answer is not written at all: the bottle stays pending and the next run asks again, so a
 * flaky connection cannot permanently record a bottle as having no ABV.
 */
class DetailsBackfill(
    private val wines: () -> List<Wine>,
    private val lookup: suspend (Wine) -> SourcedNotes,
    private val save: suspend (String, Double?, SourcedNotes) -> Unit,
    private val scope: CoroutineScope,
    private val workers: Int = 3
) {
    data class Progress(
        val running: Boolean = false,
        val done: Int = 0,
        val total: Int = 0,
        val current: String = "",
        /** Bottles this run added published notes to. */
        val notes: Int = 0,
        /** Bottles this run added an ABV to. */
        val abv: Int = 0,
        /** Lookups that did not complete. Nothing was written, so the next run asks again. */
        val failed: Int = 0,
        val message: String? = null
    )

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()
    private var job: Job? = null

    /**
     * @param again re-asks the bottles that are still missing something even though a lookup has
     *   already answered for them, which is the only way a page that appears later can be found.
     */
    fun start(again: Boolean = false) {
        if (job?.isActive == true) return
        val pending = pending(wines(), again)
        if (pending.isEmpty()) {
            _progress.value = Progress(message = "Every bottle has its details.")
            return
        }
        job = scope.launch {
            val next = AtomicInteger(0)
            val done = AtomicInteger(0)
            val notes = AtomicInteger(0)
            val abv = AtomicInteger(0)
            val failed = AtomicInteger(0)
            val problem = AtomicReference<String?>(null)
            val stop = AtomicBoolean(false)
            _progress.value = Progress(running = true, total = pending.size)

            val crew = (1..workers.coerceAtLeast(1)).map {
                launch {
                    while (isActive && !stop.get()) {
                        val index = next.getAndIncrement()
                        if (index >= pending.size) break
                        val wine = pending[index]
                        _progress.update { it.copy(current = wine.title) }
                        try {
                            val result = lookup(wine)
                            val figure = acceptable(result.alcohol)
                            save(wine.id, figure, result)
                            if (result.notes.isNotBlank()) notes.incrementAndGet()
                            if (figure != null && wine.alcohol == null) abv.incrementAndGet()
                            failed.set(0)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // A rejected key fails every bottle, so one is enough; anything else
                            // gets three tries. Nothing is saved, so those bottles stay pending.
                            failed.incrementAndGet()
                            problem.set(e.message ?: "The lookup failed.")
                            if (e is AiException && e.fatal) stop.set(true)
                            if (failed.get() >= 3) stop.set(true)
                            continue
                        }
                        _progress.update {
                            it.copy(
                                done = done.incrementAndGet(),
                                notes = notes.get(),
                                abv = abv.get(),
                                failed = failed.get()
                            )
                        }
                    }
                }
            }
            crew.joinAll()

            val finished = done.get()
            val summary = buildString {
                append(
                    "Looked up $finished of ${pending.size}: ${notes.get()} gained published notes, " +
                        "${abv.get()} gained an ABV."
                )
                if (failed.get() > 0) {
                    append(" ${failed.get()} did not answer and will be asked again next time.")
                }
                if (finished < pending.size && failed.get() == 0) {
                    append(" Stopped early; tap again to carry on from here.")
                }
                problem.get()?.let { append("\n").append(it) }
            }
            _progress.value = Progress(
                running = false,
                done = finished,
                total = pending.size,
                notes = notes.get(),
                abv = abv.get(),
                failed = failed.get(),
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

    companion object {
        /**
         * Bottles with something still to fill in, and not already answered for. A failed lookup
         * writes nothing, so it comes back here on the next run; only a real answer — a figure, or
         * pages read that print none — is stamped.
         */
        fun pending(wines: List<Wine>, again: Boolean = false): List<Wine> = wines.filter {
            val named = it.name.isNotBlank() || it.producer.isNotBlank()
            // "Again" ignores the stamps: the bottle is still missing a fact, and the only way a
            // page published later can reach it is to ask once more.
            val wanted = if (again) {
                it.notesCheckedAt == 0L || it.alcohol == null
            } else {
                it.notesCheckedAt == 0L || (it.alcohol == null && it.alcoholCheckedAt == 0L)
            }
            named && wanted
        }

        /**
         * The figure a lookup is allowed to store: one decimal place, inside the range a wine can
         * actually be. Anything else is a typo or a muddled excerpt, and stays missing.
         */
        fun acceptable(alcohol: Double?): Double? = alcohol
            ?.takeIf { it.isFinite() && it in PalateProfile.ALCOHOL_RANGE }
            ?.let { Math.round(it * 10.0) / 10.0 }
    }
}
