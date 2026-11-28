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
import java.util.Base64

/**
 * A whole-cellar backup: wines, storage locations, the hand-written mytaste.md, the currency and
 * the cellar book. One JSON file the user owns and can move between phones.
 *
 * The drink log is here because it stopped being a side effect of the minus button: a night written
 * into the book by hand is the user's own record, and a backup that left it behind would lose the
 * one thing the journal exists to keep.
 *
 * The Groq key is deliberately not included — a backup is something people email to themselves.
 *
 * The label pictures travel with the records, base64 in the same file. A restore that brought back
 * the facts of a cellar and none of its labels was not the record the journal is for.
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
    val drinks: List<Drink> = emptyList(),
    /**
     * The label pictures, by the file name the bottle records. Base64 JPEG, so the backup stays one
     * file the user can put anywhere. Empty in a backup written before 1.2.45.
     */
    val photos: Map<String, String> = emptyMap()
) {
    val bottles: Int get() = cellar.wines.sumOf { it.quantity }
}

object Backup {

    const val FORMAT = "wineverdict-backup"
    // The old app's exports, so a cellar moves across the rename with Settings → Backup.
    private val ACCEPTED_FORMATS = setOf(FORMAT, "vinoteca-backup")
    // 2 added the cellar book, 3 the label pictures. An older file still decodes: [BackupFile.drinks]
    // and [BackupFile.photos] default empty.
    const val VERSION = 3

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
        drinks: List<Drink> = emptyList(),
        photos: Map<String, ByteArray> = emptyMap()
    ): String =
        json.encodeToString(
            BackupFile(
                appVersion = appVersion,
                currency = currency,
                taste = taste,
                cellar = cellar,
                drinks = drinks,
                photos = photos.mapValues { (_, bytes) -> Base64.getEncoder().encodeToString(bytes) }
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

    /**
     * The pictures a backup carries, decoded, by the file names its records use.
     *
     * A name that is not a plain file name is dropped rather than trusted: the map comes from a file
     * somebody else could have written, and `../` in it would put a picture outside the folder the
     * app owns.
     */
    fun photos(file: BackupFile): Map<String, ByteArray> =
        file.photos.entries.mapNotNull { (name, encoded) ->
            if (!isPlainPhotoName(name)) return@mapNotNull null
            val bytes = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull()
            if (bytes == null || bytes.isEmpty()) null else name to bytes
        }.toMap()

    /** "1 label picture", "3 label pictures" — a count said the way it is read. */
    fun pictureWords(count: Int): String =
        if (count == 1) "1 label picture" else "$count label pictures"

    private fun isPlainPhotoName(name: String): Boolean =
        name.isNotBlank() &&
            name.length <= 120 &&
            name.none { it == '/' || it == '\\' || it.isWhitespace() } &&
            (name.endsWith(".jpg") || name.endsWith(".jpeg"))

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

/**
 * When a cellar should ask for a backup.
 *
 * The export is the only copy that leaves the phone, and for a long time nothing ever said so: a
 * phone lost or wiped took the cellar with it. The nudge is due when the shelf holds bottles and the
 * last backup is more than a month old — or there has never been one — and **Not now** buys the same
 * quiet month rather than silencing it for good.
 */
object BackupNudge {

    private const val DAYS = 30L
    private const val DAY_MS = 24L * 60L * 60L * 1000L

    fun due(hasBottles: Boolean, lastBackupAt: Long, dismissedAt: Long, now: Long): Boolean {
        if (!hasBottles) return false
        if (lastBackupAt > 0 && now - lastBackupAt < DAYS * DAY_MS) return false
        if (dismissedAt > 0 && now - dismissedAt < DAYS * DAY_MS) return false
        return true
    }

    fun words(lastBackupAt: Long, now: Long): String {
        if (lastBackupAt <= 0) {
            return "This cellar has never left the phone. One tap writes it to a file of your own."
        }
        val days = (now - lastBackupAt) / DAY_MS
        val said = if (days < 60) "$days days ago" else "${days / 30} months ago"
        return "The last backup was $said. A new phone starts from that file, not from this one."
    }
}