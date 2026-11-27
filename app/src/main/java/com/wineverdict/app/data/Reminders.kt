package com.wineverdict.app.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.wineverdict.app.MainActivity
import com.wineverdict.app.R
import java.util.concurrent.TimeUnit

/**
 * The one reminder the app sends.
 *
 * A wine list is photographed at a table, and the verdict on what was poured is worth most while
 * the evening is still in reach — an hour later, not a week later when the bottle is a name in a
 * photo. The reminder is scheduled when the list is read and says nothing about the wines: it is a
 * nudge to open the app, not a second copy of the answer.
 *
 * WorkManager rather than an alarm: the reminder has to outlive the screen closing and the process
 * being killed, and a one-hour delay is the shape a one-time work request already has. Nothing is
 * ever posted unless a list was actually scanned, so an install that never photographs a menu gets
 * no notification at all.
 */
object Reminders {

    /** The channel's id; one channel, because there is one kind of reminder. */
    const val CHANNEL = "review"

    private const val WORK = "list-review"
    private const val NOTIFICATION_ID = 1201

    /** The intent extra that turns the tap into that evening's list rather than the cellar. */
    const val EXTRA_LIST = "com.wineverdict.app.extra.LIST"

    /** Work input: which list the nudge is about. Internal so the worker can read it back. */
    internal const val KEY_LIST = "list"

    /** How long after a list is read the nudge arrives. */
    const val DELAY_MINUTES = 60L

    /**
     * Books the nudge for one kept list. A second list replaces the first one's reminder: two
     * notifications for one evening is noise, and the newest list is the one whose wines are still
     * in reach. The list's id travels with the work, so the notification opens that list.
     */
    fun scheduleListReview(context: Context, listId: String?) {
        val request = OneTimeWorkRequestBuilder<ListReviewWorker>()
            .setInitialDelay(DELAY_MINUTES, TimeUnit.MINUTES)
            .setInputData(
                if (listId.isNullOrBlank()) workDataOf() else workDataOf(KEY_LIST to listId)
            )
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, request)
    }

    /** Drops a pending nudge, for a list that has been answered already. */
    fun cancelListReview(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }

    /** Whether the app may post at all. False only on 13+ before the one-time grant. */
    fun canNotify(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    fun post(context: Context, listId: String?) {
        // The worker can run after the permission was revoked: the reminder is then dropped rather
        // than thrown, because a notification nobody allowed is not worth a crash report.
        if (!canNotify(context)) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                "Wine list review",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "One nudge an hour after a wine list is scanned, before the evening " +
                    "is forgotten."
            }
        )
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                if (!listId.isNullOrBlank()) putExtra(EXTRA_LIST, listId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("How was the wine?")
            .setContentText(
                if (listId.isNullOrBlank()) {
                    "You read a wine list an hour ago. Give what you drank a verdict while you " +
                        "still remember it."
                } else {
                    "The list you kept is an hour old. Open it and say what the wines were like " +
                        "while you still remember."
                }
            )
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    if (listId.isNullOrBlank()) {
                        "You read a wine list an hour ago. Give what you drank a verdict while " +
                            "you still remember it."
                    } else {
                        "The list you kept is an hour old. Tap to reopen it and say what the " +
                            "wines were like — every wine on it can be given a verdict, and the " +
                            "ones you want to remember can be kept with no bottles."
                    }
                )
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }
}

/** Runs an hour after a list was read, even if the app has been closed since. */
class ListReviewWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        Reminders.post(applicationContext, inputData.getString(Reminders.KEY_LIST))
        return Result.success()
    }
}
