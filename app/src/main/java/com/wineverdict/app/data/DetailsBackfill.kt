package com.wineverdict.app.data

import com.wineverdict.app.ai.AiException
import com.wineverdict.app.ai.DetailsFacts
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
 * Fills in what a bottle is missing — published notes, flavour words, the ABV, the drinking window,
 * the place and the grapes — in one pass.
 *
 * There used to be two jobs and two buttons, and a bottle needing both went through the queue twice:
 * once for notes and once for the alcohol figure, at about six seconds a bottle, which is five
 * minutes for a cellar of fifty. One lookup answers all of it (the extraction asks for the note and
 * every fact beside it), so this walks each incomplete bottle once, and only asks the targeted ABV
 * question when the pages carried no figure.
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
        /** Bottles this run gained a drinking window for. */
        val windows: Int = 0,
        /** Bottles this run gained a country, region or appellation for. */
        val place: Int = 0,
        /** Bottles this run gained grapes for. */
        val grapes: Int = 0,
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
            val windows = AtomicInteger(0)
            val place = AtomicInteger(0)
            val grapes = AtomicInteger(0)
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
                            val (from, to) = acceptableWindow(result.drinkFrom, result.drinkTo)
                            save(wine.id, figure, result.copy(drinkFrom = from, drinkTo = to))
                            if (result.notes.isNotBlank()) notes.incrementAndGet()
                            if (figure != null && wine.alcohol == null) abv.incrementAndGet()
                            if (needsWindow(wine) && (from != null || to != null)) {
                                windows.incrementAndGet()
                            }
                            if (fillsPlace(wine, result)) place.incrementAndGet()
                            if (wine.grapes.isEmpty() && result.grapes.isNotEmpty()) {
                                grapes.incrementAndGet()
                            }
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
                                windows = windows.get(),
                                place = place.get(),
                                grapes = grapes.get(),
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
                        "${abv.get()} an ABV, ${windows.get()} a drinking window, " +
                        "${place.get()} a place and ${grapes.get()} their grapes."
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
                windows = windows.get(),
                place = place.get(),
                grapes = grapes.get(),
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
         * The facts this bottle does not hold yet, one word each: notes, ABV, window, place, grapes.
         * Everything a lookup can answer is here, so this is also what "still missing" counts.
         */
        fun missing(wine: Wine): List<String> = buildList {
            if (wine.expertNotes.isBlank()) add("notes")
            if (wine.alcohol == null) add("ABV")
            if (needsWindow(wine)) add("window")
            if (needsPlace(wine)) add("place")
            if (wine.grapes.isEmpty()) add("grapes")
        }

        /** Bottles with a fact still absent, which is what the card offers to look at again. */
        fun stillMissing(wines: List<Wine>): List<Wine> =
            wines.filter { named(it) && missing(it).isNotEmpty() }

        fun needsWindow(wine: Wine): Boolean = wine.drinkFrom == null && wine.drinkTo == null

        fun needsPlace(wine: Wine): Boolean =
            wine.region.isBlank() && wine.country.isBlank() && wine.appellation.isBlank()

        /** True when the answer names a place this record does not have. */
        fun fillsPlace(wine: Wine, result: SourcedNotes): Boolean = needsPlace(wine) &&
            (result.country.isNotBlank() || result.region.isNotBlank() ||
                result.appellation.isNotBlank())

        private fun named(wine: Wine): Boolean =
            wine.name.isNotBlank() || wine.producer.isNotBlank()

        /**
         * Bottles with something still to fill in, and not already answered for. A failed lookup
         * writes nothing, so it comes back here on the next run; only a real answer — a figure, a
         * window, or pages read that print none — is stamped.
         */
        fun pending(wines: List<Wine>, again: Boolean = false): List<Wine> = wines.filter {
            if (!named(it)) return@filter false
            // "Again" ignores the stamps: the bottle is still missing a fact, and the only way a
            // page published later can reach it is to ask once more.
            if (again) missing(it).isNotEmpty() else neverAsked(it)
        }

        /**
         * The facts no lookup has answered for yet. A bottle imported with notes but never checked
         * still counts: the lookup is what gives those notes a source and fills the rest.
         */
        private fun neverAsked(wine: Wine): Boolean =
            wine.notesCheckedAt == 0L ||
                (wine.alcohol == null && wine.alcoholCheckedAt == 0L) ||
                (needsWindow(wine) && wine.windowCheckedAt == 0L) ||
                (needsPlace(wine) && wine.placeCheckedAt == 0L) ||
                (wine.grapes.isEmpty() && wine.grapesCheckedAt == 0L)

        /**
         * The window a lookup is allowed to store: two years in the order a window goes, each one a
         * year a bottle can be alive in. Reversed or stray numbers are no window at all.
         */
        fun acceptableWindow(from: Int?, to: Int?): Pair<Int?, Int?> = DetailsFacts.sane(from, to)

        /**
         * The figure a lookup is allowed to store: one decimal place, inside the range a wine can
         * actually be. Anything else is a typo or a muddled excerpt, and stays missing.
         */
        fun acceptable(alcohol: Double?): Double? = alcohol
            ?.takeIf { it.isFinite() && it in PalateProfile.ALCOHOL_RANGE }
            ?.let { Math.round(it * 10.0) / 10.0 }
    }
}
