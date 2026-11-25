package com.wineverdict.app.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.wineverdict.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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

    private val userAgent = "WineVerdict/${BuildConfig.VERSION_NAME} (Android)"

    /** The newest release when it is newer than this build; null when this build is current. */
    suspend fun check(): Release? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$OWNER/$REPO/releases?per_page=20")
            .addHeader("Accept", "application/vnd.github+json")
            .addHeader("User-Agent", userAgent)
            .get()
            .build()
        val body = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException(
                    when (response.code) {
                        403 -> "GitHub is limiting requests from this phone. Try again later."
                        else -> "GitHub answered HTTP ${response.code}."
                    }
                )
            }
            response.body?.string().orEmpty()
        }
        val release = parseList(body) ?: throw IOException("No update is published for this app yet.")
        release.takeIf { isNewer(it.version, BuildConfig.VERSION_NAME) }
    }

    /** Downloads [release] into the cache, reporting progress from 0 to 1. */
    suspend fun download(release: Release, onProgress: (Float) -> Unit = {}): File =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(release.url)
                .addHeader("User-Agent", userAgent)
                .addHeader("Accept", "application/octet-stream")
                .get()
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("The download failed (HTTP ${response.code}).")
                }
                val body = response.body ?: throw IOException("The download was empty.")
                val total = body.contentLength().takeIf { it > 0 } ?: release.bytes
                val dir = directory()
                // Drop interrupted downloads and APKs from earlier checks so the installer
                // never picks up a stale file.
                dir.listFiles()?.forEach { file ->
                    if (file.name.endsWith(".part") || (file.name.endsWith(".apk") && file.name != release.fileName)) {
                        file.delete()
                    }
                }
                val part = File(dir, "${release.fileName}.part")
                part.delete()
                var copied = 0L
                var reported = -1
                try {
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
                } catch (e: Exception) {
                    part.delete()
                    throw e
                }
                if (release.bytes > 0 && copied != release.bytes) {
                    part.delete()
                    throw IOException("The download was cut off ($copied of ${release.bytes} bytes). Try again.")
                }
                val apk = File(dir, release.fileName)
                if (apk.exists()) apk.delete()
                if (!part.renameTo(apk)) {
                    part.copyTo(apk, overwrite = true)
                    part.delete()
                }
                if (!signaturesMatch(apk)) {
                    apk.delete()
                    throw IOException("That file was not signed by Wine Verdict, so Android would refuse it. Try again later.")
                }
                onProgress(1f)
                apk
            }
        }

    /** True when a downloaded APK carries this app's own signature and Android will accept it. */
    fun signaturesMatch(apk: File): Boolean = runCatching {
        val pm = context.packageManager
        val current = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
        } ?: return false
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val archive = pm.getPackageArchiveInfo(apk.absolutePath, flags) ?: return false
        val candidate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archive.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            archive.signatures
        } ?: return false
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val ours = current.map { digest.digest(it.toByteArray()).toList() }.toSet()
        val theirs = candidate.map { digest.digest(it.toByteArray()).toList() }.toSet()
        ours.isNotEmpty() && ours == theirs
    }.getOrDefault(false)

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

    /**
     * The newest non-draft release carrying this app's own APK. The repo publishes two product
     * lines, so /releases/latest can point at the other one — a Wine Verdict build must never
     * offer a Vinoteca APK as its own update, whatever the version numbers say.
     */
    private fun parseList(body: String): Release? = runCatching {
        val releases = Json.parseToJsonElement(body) as? JsonArray ?: return null
        releases.firstNotNullOfOrNull { element ->
            val root = element as? JsonObject ?: return@firstNotNullOfOrNull null
            if (root.boolean("draft")) return@firstNotNullOfOrNull null
            parseRelease(root)
        }
    }.getOrNull()

    private fun parseRelease(root: JsonObject): Release? {
        val version = root.string("tag_name")?.removePrefix("v") ?: return null
        val apk = (root["assets"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { asset ->
                val name = asset.string("name") ?: return@firstOrNull false
                name.startsWith(ASSET_PREFIX, ignoreCase = true) &&
                    name.endsWith(".apk", ignoreCase = true)
            } ?: return null
        return Release(
            version = version,
            notes = root.string("body").orEmpty(),
            url = apk.string("browser_download_url") ?: return null,
            fileName = apk.string("name") ?: return null,
            bytes = apk.long("size")
        )
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.boolean(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.contentOrNull == "true"

    private fun JsonObject.long(key: String): Long =
        when (val value = this[key]) {
            is JsonPrimitive -> value.contentOrNull?.toLongOrNull()
                ?: value.toString().trim('"').toLongOrNull()
                ?: 0L
            else -> 0L
        }

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
        private const val REPO = "wine-verdict"
        private const val ASSET_PREFIX = "wine-verdict-"
    }
}
