package com.wineverdict.app.data

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the last fatal error on disk, so a crash on a phone nobody can attach a debugger to can
 * still be read.
 *
 * A force close used to leave one fact behind — "Wine Verdict keeps stopping" — and nothing about
 * which screen, which value or which line. The reporter wraps the process's uncaught-exception
 * handler, writes the stack to `files/last-crash.txt`, and then hands the error to the handler that
 * was already there: it records the crash, it never prevents or hides one.
 *
 * Nothing leaves the phone. The file is read only when Settings asks for it, and the row that shows
 * it can copy the text out or forget it.
 */
object CrashLog {

    private const val FILE = "last-crash.txt"

    /** A stack trace this long is already more than anyone reads; the rest is noise. */
    private const val MAX_CHARS = 6_000

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                File(app.filesDir, FILE).writeText(describe(error, header(thread)))
            }
            // The app still dies: letting the platform have it keeps the normal crash behaviour,
            // the restart, and the system's own reporting.
            previous?.uncaughtException(thread, error)
        }
    }

    fun read(context: Context): String? {
        val file = File(context.filesDir, FILE)
        if (!file.exists()) return null
        return runCatching { file.readText().trim().ifBlank { null } }.getOrNull()
    }

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE).delete() }
    }

    /**
     * The text the row shows and the copy button puts on the clipboard. Pure, so the shape can be
     * tested without a device.
     */
    fun describe(error: Throwable, header: String, at: Date = Date()): String {
        val trace = StringWriter().also { writer ->
            PrintWriter(writer).use { error.printStackTrace(it) }
        }.toString()
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(at)
        return buildString {
            append(stamp).append(" · ").append(header).append('\n')
            append(trace.trimEnd())
        }.take(MAX_CHARS)
    }

    private fun header(thread: Thread): String = buildString {
        append("Wine Verdict ")
        append(versionName())
        append(" · Android ").append(android.os.Build.VERSION.RELEASE)
        append(" · ").append(android.os.Build.MANUFACTURER).append(' ')
        append(android.os.Build.MODEL)
        append(" · thread ").append(thread.name)
    }

    private fun versionName(): String =
        runCatching { com.wineverdict.app.BuildConfig.VERSION_NAME }.getOrDefault("?")
}
