package com.wineverdict.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

object ImageUtils {

    /** A cache file wrapped in the app's FileProvider, ready for ACTION_IMAGE_CAPTURE. */
    fun newCaptureUri(context: Context): Uri {
        val dir = File(context.cacheDir, "captures").apply { mkdirs() }
        val photo = File.createTempFile("capture_", ".jpg", dir)
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", photo)
    }

    /** Down-scaled JPEG bytes, small enough for a vision request. */
    fun jpegBytes(context: Context, uri: Uri, maxDim: Int = 1400, quality: Int = 88): ByteArray {
        val upright = loadUpright(context, uri, maxDim)
        return compress(scaleDown(upright, maxDim), quality)
    }

    /**
     * The middle of the photo, enlarged. Small print — the vintage, the appellation, the
     * "mis en bouteille" line — is the first thing a phone photo loses, and the first thing the
     * model needs, so the label scan sends this alongside the full frame.
     */
    fun labelCropJpeg(context: Context, uri: Uri, maxDim: Int = 1600, keep: Float = 0.78f): ByteArray {
        val upright = loadUpright(context, uri, 2400)
        val cropWidth = (upright.width * keep).toInt().coerceIn(1, upright.width)
        val cropHeight = (upright.height * keep).toInt().coerceIn(1, upright.height)
        val cropped = Bitmap.createBitmap(
            upright,
            (upright.width - cropWidth) / 2,
            (upright.height - cropHeight) / 2,
            cropWidth,
            cropHeight
        )
        val shortest = minOf(cropped.width, cropped.height)
        val enlarged = if (shortest < 900) {
            val ratio = 900f / shortest.toFloat()
            Bitmap.createScaledBitmap(
                cropped,
                (cropped.width * ratio).toInt().coerceAtLeast(1),
                (cropped.height * ratio).toInt().coerceAtLeast(1),
                true
            )
        } else {
            cropped
        }
        return compress(scaleDown(enlarged, maxDim), 90)
    }

    private fun loadUpright(context: Context, uri: Uri, maxDim: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IOException("That file does not look like an image")
        }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxDim && bounds.outHeight / (sample * 2) >= maxDim) {
            sample *= 2
        }
        val bitmap = context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IOException("Could not read the image")
        return applyExifRotation(context, uri, bitmap)
    }

    private fun compress(bitmap: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.toByteArray()
        }

    /**
     * Small bitmap used for on-screen previews. Decoded and scaled once: the old version
     * JPEG-encoded the bitmap and decoded it straight back, paying a full round trip to show
     * the same pixels.
     */
    fun previewBitmap(context: Context, uri: Uri, maxDim: Int = 900): Bitmap? = runCatching {
        scaleDown(loadUpright(context, uri, maxDim), maxDim)
    }.getOrNull()

    private fun scaleDown(source: Bitmap, maxDim: Int): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= maxDim) return source
        val ratio = maxDim.toFloat() / longest.toFloat()
        val width = (source.width * ratio).toInt().coerceAtLeast(1)
        val height = (source.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, width, height, true)
    }

    private fun applyExifRotation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap = runCatching {
        val degrees = context.contentResolver.openInputStream(uri)?.use { stream ->
            when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        if (degrees == 0f) {
            bitmap
        } else {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees) }, true)
        }
    }.getOrDefault(bitmap)
}