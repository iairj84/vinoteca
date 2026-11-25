package com.vinoteca.app.data

import android.content.Context
import android.net.Uri
import com.vinoteca.app.model.CellarData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.FileNotFoundException
import java.nio.charset.Charset

/**
 * A whole-cellar backup: wines, storage locations, the hand-written mytaste.md and the
 * currency. One JSON file the user owns and can move between phones.
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
    val cellar: CellarData = CellarData()
) {
    val bottles: Int get() = cellar.wines.sumOf { it.quantity }
}

object Backup {

    const val FORMAT = "vinoteca-backup"
    const val VERSION = 1

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun encode(cellar: CellarData, taste: String, appVersion: String, currency: String): String =
        json.encodeToString(
            BackupFile(
                appVersion = appVersion,
                currency = currency,
                taste = taste,
                cellar = cellar
            )
        )

    fun decode(text: String): BackupFile {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{")) {
            throw IllegalArgumentException(
                "That file is not a Vinoteca backup. Backups are the .json file the app exports."
            )
        }
        val parsed = runCatching { json.decodeFromString<BackupFile>(trimmed) }.getOrElse {
            throw IllegalArgumentException("That file could not be read as a Vinoteca backup.")
        }
        if (parsed.format.isNotBlank() && parsed.format != FORMAT) {
            throw IllegalArgumentException("That backup was made by something else (${parsed.format}).")
        }
        if (parsed.cellar.wines.isEmpty() && parsed.taste.isBlank()) {
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
        "vinoteca-backup-${java.time.LocalDate.now()}.json"
}