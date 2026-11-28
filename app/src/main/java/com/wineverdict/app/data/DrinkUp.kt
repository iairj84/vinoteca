package com.wineverdict.app.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.wineverdict.app.model.DrinkStatus
import com.wineverdict.app.model.Wine
import com.wineverdict.app.model.drinkStatus
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime
import java.time.Year
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.TimeUnit

/** What the weekly nudge says, worked out here so it can be read without a phone. */
data class DrinkUpDigest(val title: String, val body: String)

/**
 * The bottles that want drinking: at their peak, or past the window on their record.
 *
 * The cellar has always known this — every row carries a window badge and **Ready now** counts
 * READY and PEAK — but nothing ever said *these three, this week*, so a bottle could sit past its
 * last good year in a list of two hundred with nothing raising its hand.
 *
 * Two rules keep the view honest. Only stock counts: a wine kept off a list, or one all drunk, has
 * no bottle to open. And only [PEAK] and [PAST] count: **drink now** is a bottle you may open, not
 * one you must, and calling every ready bottle urgent would make the view the whole cellar.
 */
object DrinkUp {

    /** The two states that are a decision. Ordered by urgency, which is also how they sort. */
    val DUE = listOf(DrinkStatus.PEAK, DrinkStatus.PAST)

    /** In the cellar and at its peak or beyond, the most urgent first. */
    fun bottles(wines: List<Wine>, year: Int = Year.now().value): List<Wine> =
        wines.filter { it.quantity > 0 && it.drinkStatus(year) in DUE }
            .sortedWith(
                compareByDescending<Wine> { it.drinkStatus(year).ordinal }
                    .thenBy { it.title.lowercase() }
            )

    /**
     * The one notification a week, or null when there is nothing to say.
     *
     * Null rather than "nothing to drink up": a nudge that arrives to say nothing is the kind of
     * notification people turn off, and the switch has to stay worth leaving on.
     */
    fun digest(wines: List<Wine>, year: Int = Year.now().value): DrinkUpDigest? {
        val bottles = bottles(wines, year)
        if (bottles.isEmpty()) return null
        val past = bottles.count { it.drinkStatus(year) == DrinkStatus.PAST }
        val peak = bottles.size - past
        val names = bottles.take(3).joinToString(" · ") { it.title } +
            if (bottles.size > 3) " and ${bottles.size - 3} more" else ""
        val parts = buildList {
            if (past > 0) add(if (past == 1) "one past its window" else "$past past their window")
            if (peak > 0) add(if (peak == 1) "one at its peak" else "$peak at their peak")
        }.joinToString(", ")
        val title = when {
            peak == 0 && bottles.size == 1 -> "One bottle past its window"
            peak == 0 -> "${bottles.size} bottles past their window"
            past == 0 && bottles.size == 1 -> "One bottle at its peak"
            past == 0 -> "${bottles.size} bottles at their peak"
            bottles.size == 1 -> "One bottle to drink up"
            else -> "${bottles.size} bottles to drink up"
        }
        return DrinkUpDigest(title = title, body = "$names — $parts.")
    }
}

/**
 * The weekly nudge, opted into in Settings and off until it is.
 *
 * WorkManager rather than an alarm for the same reason the list reminder is: it has to survive the
 * app being closed, and a phone that reboots should not lose it. The first one lands on a Monday
 * morning — a bottle is a decision for the week rather than for tonight — and every seven days
 * after that, so the nudge keeps a shape the user can plan around instead of arriving whenever the
 * switch was flipped.
 *
 * Work that is already booked is UPDATEd rather than replaced, so opening the app does not push
 * this week's nudge back another week.
 */
object WeeklyDigest {

    const val WORK = "drink-up-weekly"

    /** Monday morning, nine o'clock, in the phone's own time zone. */
    private val MORNING = LocalTime.of(9, 0)

    /** The next Monday at nine that is still ahead of [now]. */
    fun nextAfter(now: ZonedDateTime): ZonedDateTime {
        val monday = now.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY)).with(MORNING)
        return if (monday.isAfter(now)) monday else monday.plusWeeks(1)
    }

    fun delayMillis(now: ZonedDateTime = ZonedDateTime.now()): Long =
        Duration.between(now, nextAfter(now)).toMillis()

    fun schedule(context: Context, now: ZonedDateTime = ZonedDateTime.now()) {
        val request = PeriodicWorkRequestBuilder<DrinkUpWorker>(7, TimeUnit.DAYS)
            .setInitialDelay(delayMillis(now), TimeUnit.MILLISECONDS)
            // Reading a file and posting a notification is not worth waking a nearly flat phone.
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }
}

/**
 * One weekly run: read the cellar, and post only when a bottle wants drinking.
 *
 * It reads the file rather than the running app, because the whole point is that the app is closed.
 * The setting is checked again here: a request left behind by an opt-in that was since turned off
 * must not post anything.
 */
class DrinkUpWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val app = applicationContext
        if (!SettingsStore(app).weeklyDigest) return Result.success()
        val cellar = CellarRepository(app)
        cellar.load()
        val digest = DrinkUp.digest(cellar.data.value.wines) ?: return Result.success()
        Reminders.postDrinkUp(app, digest)
        return Result.success()
    }
}
