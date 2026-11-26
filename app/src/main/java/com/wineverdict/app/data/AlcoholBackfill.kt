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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Fills the ABV the alcohol gate reads, one bottle at a time.
 *
 * A gate cannot be checked against a fact the record does not have. Most of a scanned cellar arrives
 * without an ABV — 48 of 50 bottles here — so G1 was [GateVerdict.UNKNOWN] on nearly everything and
 * could neither pass nor fail. This works through the bottles that still have no number, asks the
 * same grounded lookup the notes backfill uses, and saves only what a page actually printed.
 *
 * Two rules keep it honest. The figure must be one-decimal and inside [PalateProfile.ALCOHOL_RANGE],
 * because "14" on a page and "40" from a muddled excerpt are not the same claim. And a miss is
 * stamped in [Wine.alcoholCheckedAt] rather than estimated, so a second run does not pay to ask the
 * same question — and the stamp is separate from the notes check, so neither job blocks the other.
 *
 * The lookup also returns published notes; those are kept with their pages, because the style gate
 * reads them and a sourced note is worth more than a guessed one.
 */
class AlcoholBackfill(
    private val wines: () -> List<Wine>,
    private val lookup: suspend (Wine) -> SourcedNotes,
    private val save: suspend (String, Double?, SourcedNotes) -> Unit,
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
        val pending = pending(wines())
        if (pending.isEmpty()) {
            _progress.value = Progress(
                message = "Every bottle either records an ABV or has already been looked up."
            )
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
                    val result = lookup(wine)
                    val abv = acceptable(result.alcohol)
                    save(wine.id, abv, result)
                    if (abv == null) missed++ else found++
                    failures = 0
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Same policy as the notes backfill: a bad key fails every bottle, so stop at
                    // the first one; anything else gets three tries before giving up.
                    failures++
                    problem = e.message ?: "The lookup failed."
                    if (e is AiException && e.fatal) break
                    if (failures >= 3) break
                    continue
                }
                done++
                _progress.value = Progress(true, done, pending.size, wine.title, found, missed)
            }

            val summary = buildString {
                append(
                    "Looked up $done of ${pending.size}: $found with an ABV on a page, " +
                        "$missed with none found."
                )
                if (done < pending.size) append(" Stopped early; tap again to carry on from here.")
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

    companion object {
        /** Bottles the gate cannot check and this job has not already asked about. */
        fun pending(wines: List<Wine>): List<Wine> = wines.filter {
            it.alcohol == null &&
                it.alcoholCheckedAt == 0L &&
                (it.name.isNotBlank() || it.producer.isNotBlank())
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
