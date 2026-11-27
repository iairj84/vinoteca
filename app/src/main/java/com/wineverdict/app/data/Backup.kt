package com.wineverdict.app.data

import android.content.Context
import android.net.Uri
import com.wineverdict.app.model.CellarData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.FileNotFoundException
import java.nio.charset.Charset

/**
 * A whole-cellar backup: wines, storage locations, the hand-written mytaste.md, the currency and
 * the cellar book. One JSON file the user owns and can move between phones.
 *
 * The drink log is here because it stopped being a side effect of the minus button: a night written
 * into the book by hand is the user's own record, and a backup that left it behind would lose the
 * one thing the journal exists to keep.
 *
 * The Groq key is deliberately not included — a backup is something people email to themselves.
 */
@Serializable
data class BackupFile(
    val format: String = Backup.FORMAT,
    val version: Int = Backup.VERSION,
    val exportedAt: Long = System.currentTimeMillis(),
    val appVersion: String = "",
    val currency: String = "",
    val taste: String = "",
    val cellar: CellarData = CellarData(),
    /** Every night on record. Empty in a backup written before the cellar book existed. */
    val drinks: List<Drink> = emptyList()
) {
    val bottles: Int get() = cellar.wines.sumOf { it.quantity }
}

object Backup {

    const val FORMAT = "wineverdict-backup"
    // The old app's exports, so a cellar moves across the rename with Settings → Backup.
    private val ACCEPTED_FORMATS = setOf(FORMAT, "vinoteca-backup")
    // 2 added the cellar book. A version 1 file still decodes: [BackupFile.drinks] defaults empty.
    const val VERSION = 2

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun encode(
        cellar: CellarData,
        taste: String,
        appVersion: String,
        currency: String,
        drinks: List<Drink> = emptyList()
    ): String =
        json.encodeToString(
            BackupFile(
                appVersion = appVersion,
                currency = currency,
                taste = taste,
                cellar = cellar,
                drinks = drinks
            )
        )

    fun decode(text: String): BackupFile {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{")) {
            throw IllegalArgumentException(
                "That file is not a Wine Verdict backup. Backups are the .json file the app exports."
            )
        }
        val parsed = runCatching { json.decodeFromString<BackupFile>(trimmed) }.getOrElse {
            throw IllegalArgumentException("That file could not be read as a Wine Verdict backup.")
        }
        if (parsed.format.isNotBlank() && parsed.format !in ACCEPTED_FORMATS) {
            throw IllegalArgumentException("That backup was made by something else (${parsed.format}).")
        }
        if (parsed.cellar.wines.isEmpty() && parsed.taste.isBlank() && parsed.drinks.isEmpty()) {
            throw IllegalArgumentException("That backup is empty.")
        }
        return parsed
    }

    suspend fun readBytes(context: Context, uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw FileNotFoundException("Could not open that file.")
    }

    suspend fun write(context: Context, uri: Uri, text: String) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
        } ?: throw FileNotFoundException("Could not write to that location.")
    }

    /** Exports are UTF-8, but older CellarTracker / Excel files are often windows-1252. */
    fun decodeText(bytes: ByteArray): String {
        val utf8 = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
        return if (utf8.contains('\uFFFD')) {
            String(bytes, Charset.forName("windows-1252"))
        } else {
            utf8
        }
    }

    fun suggestedFileName(): String =
        "wineverdict-backup-${java.time.LocalDate.now()}.json"
}