package com.wineverdict.app.ui

import android.content.Context
import android.net.Uri
import com.wineverdict.app.AppGraph
import com.wineverdict.app.BuildConfig
import com.wineverdict.app.data.Backup

/**
 * Writes the whole-cellar backup and answers the line the screen shows afterwards.
 *
 * One implementation for the two places it can be started from — Settings and the nudge on the
 * cellar — so the files they write cannot drift apart. The label pictures go with the records now,
 * which is what makes the export the thing a new phone can actually be rebuilt from.
 */
suspend fun writeBackup(context: Context, uri: Uri): String {
    val cellar = AppGraph.cellar.data.value
    val drinks = AppGraph.drinks.drinks.value
    val pictures = AppGraph.cellar.photoBytes()
    Backup.write(
        context,
        uri,
        Backup.encode(
            cellar = cellar,
            taste = AppGraph.taste.text.value,
            appVersion = BuildConfig.VERSION_NAME,
            currency = AppGraph.settings.currency,
            drinks = drinks,
            photos = pictures,
            // Saved places are drawn on this phone and cannot be looked up again anywhere: a
            // backup that left them behind would cost every Home and friend's house their name.
            places = AppGraph.places.places.value
        )
    )
    AppGraph.settings.lastBackupAt = System.currentTimeMillis()
    return buildString {
        append("Backup written: ${cellar.wines.size} wines, ")
        append("${cellar.wines.sumOf { it.quantity }} bottles, ${drinks.size} entries in the cellar book")
        if (pictures.isNotEmpty()) {
            append(", ${pictures.size} ")
            append(if (pictures.size == 1) "label picture" else "label pictures")
        }
        append(", plus your mytaste.md.")
    }
}
