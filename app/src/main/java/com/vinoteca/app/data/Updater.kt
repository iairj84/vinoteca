package com.vinoteca.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.vinoteca.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Updates from the project's own GitHub releases.
 *
 * There is no update server of ours: the release the CI publishes is the only thing the app asks
 * for, and it asks with no account and no key. A check is one small GET; the APK is downloaded only
 * when the person taps Install, into the app's own cache, which Android's installer reads through
 * the app's FileProvider.
 */
class Updater(private val context: Context) {

    data class Release(
        val version: String,
        val notes: String,
        val url: String,
        val fileName: String,
        val bytes: Long
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    private val userAgent = "Vinoteca/${BuildConfig.VERSION_NAME} (Android)"

    /** The newest release when it is newer than this build; null when this build is current. */
    suspend fun check(): Release? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$OWNER/$REPO/releases/latest")
            .addHeader("Accept", "application/vnd.github+json")
            .addHeader("User-Agent", userAgent)
            .get()
            .build()
        val body = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException(
                    when (response.code) {
                        404 -> "No release has been published yet."
                        403 -> "GitHub is limiting requests from this phone. Try again later."
                        else -> "GitHub answered HTTP ${response.code}."
                    }
                )
            }
            response.body?.string().orEmpty()
        }
        val release = parse(body) ?: throw IOException("The latest release has no APK attached.")
        release.takeIf { isNewer(it.version, BuildConfig.VERSION_NAME) }
    }

    /** Downloads [release] into the cache, reporting progress from 0 to 1. */
    suspend fun download(release: Release, onProgress: (Float) -> Unit = {}): File =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(release.url)
                .addHeader("User-Agent", userAgent)
                .get()
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("The download failed (HTTP ${response.code}).")
                }
                val body = response.body ?: throw IOException("The download was empty.")
                val total = body.contentLength().takeIf { it > 0 } ?: release.bytes
                val part = File(directory(), "${release.fileName}.part")
                var copied = 0L
                var reported = -1
                body.byteStream().use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            if (total > 0) {
                                val percent = (copied * 100 / total).toInt()
                                if (percent != reported) {
                                    reported = percent
                                    onProgress((copied.toFloat() / total).coerceIn(0f, 1f))
                                }
                            }
                        }
                    }
                }
                val apk = File(directory(), release.fileName)
                if (!part.renameTo(apk)) {
                    part.copyTo(apk, overwrite = true)
                    part.delete()
                }
                apk
            }
        }

    /** True when Android will let this app hand an APK to its installer. */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** The per-app "install unknown apps" page, for when [canInstall] says no. */
    fun installSettingsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /** Opens Android's installer on an APK this class downloaded. */
    fun install(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun directory(): File = File(context.cacheDir, "updates").apply { mkdirs() }

    private fun parse(body: String): Release? = runCatching {
        val root = Json.parseToJsonElement(body).jsonObject
        val version = root.string("tag_name")?.removePrefix("v") ?: return null
        val apk = (root["assets"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { asset ->
                asset.string("name")?.endsWith(".apk", ignoreCase = true) == true
            } ?: return null
        Release(
            version = version,
            notes = root.string("body").orEmpty(),
            url = apk.string("browser_download_url") ?: return null,
            fileName = apk.string("name") ?: return null,
            bytes = apk.string("size")?.toLongOrNull() ?: 0L
        )
    }.getOrNull()

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    /**
     * Releases are tagged with the versionName the APK was built with, so the two are directly
     * comparable. A tag that is not newer than this build is not an update, whatever its text.
     */
    private fun isNewer(candidate: String, current: String): Boolean {
        val theirs = numbers(candidate)
        val ours = numbers(current)
        for (index in 0 until maxOf(theirs.size, ours.size)) {
            val a = theirs.getOrElse(index) { 0 }
            val b = ours.getOrElse(index) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun numbers(version: String): List<Int> =
        version.removePrefix("v")
            .split('.', '-', '+')
            .map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

    companion object {
        private const val OWNER = "iairj84"
        private const val REPO = "vinoteca"
    }
}
