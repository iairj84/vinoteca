package com.wineverdict.app.ai

import android.graphics.BitmapFactory
import kotlin.math.abs

/**
 * Whether a downloaded picture is the bottle.
 *
 * The first version of the picture finder took whatever a page declared for itself, and a page's
 * declaration is often its own furniture: a shop's 976×291 "Uncork Life" banner came back as a
 * bottle's photograph, a brand page's candlelit pour for a Pinot Noir, and a producer's cork. None
 * of those are recognised in a fridge at a glance, which is the only job the picture has.
 *
 * A bottle or its label is a portrait or square crop with a plain field behind it, so two questions
 * are asked of the bytes: the shape a photograph of one bottle has, and an edge that is one colour
 * rather than a scene. Both are cheap, and both are read from the file itself rather than trusted
 * from what the page said about it.
 */
object PictureGate {

    /** Smallest side worth zooming into: below this a label's text is mush. */
    const val MIN_SIDE = 150

    private const val MIN_RATIO = 0.45f
    private const val MAX_RATIO = 1.9f

    /** Mean brightness spread around the edge a plain background has; a scene is far above. */
    private const val PLAIN_BORDER = 34.0

    /** The pixels a file declares for itself, read from its header. Null when the format is unknown. */
    fun size(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size > 24 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
        ) {
            return be32(bytes, 16) to be32(bytes, 20)
        }
        if (bytes.size > 10 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte()
        ) {
            return le16(bytes, 6) to le16(bytes, 8)
        }
        if (bytes.size > 30 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[12] == 'V'.code.toByte()
        ) {
            val kind = String(bytes, 13, 3, Charsets.US_ASCII)
            return when (kind) {
                "P8X" -> (le24(bytes, 24) + 1) to (le24(bytes, 27) + 1)
                "P8 " -> (le16(bytes, 26) and 0x3FFF) to (le16(bytes, 28) and 0x3FFF)
                "P8L" -> {
                    val b1 = bytes[21].toInt() and 0xFF
                    val b2 = bytes[22].toInt() and 0xFF
                    val b3 = bytes[23].toInt() and 0xFF
                    val b4 = bytes[24].toInt() and 0xFF
                    (((b2 and 0x3F) shl 8) or b1) + 1 to
                        (((b4 and 0x0F) shl 10) or (b3 shl 2) or ((b2 and 0xC0) shr 6)) + 1
                }
                else -> null
            }
        }
        if (bytes.size > 4 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) {
            var i = 2
            while (i < bytes.size - 9) {
                if (bytes[i] != 0xFF.toByte()) {
                    i++
                    continue
                }
                val marker = bytes[i + 1].toInt() and 0xFF
                if (marker in JPEG_FRAMES) return be16(bytes, i + 7) to be16(bytes, i + 5)
                i += 2 + be16(bytes, i + 2)
            }
        }
        return null
    }

    /** A bottle is tall or square; a banner, a strip and a wide hero are none of those. */
    fun plausible(width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        if (minOf(width, height) < MIN_SIDE) return false
        val ratio = width.toFloat() / height
        return ratio in MIN_RATIO..MAX_RATIO
    }

    /** One colour around the edge, as a product shot is, rather than a room, a table or a hand. */
    fun plainBorder(pixels: IntArray, width: Int, height: Int): Boolean {
        if (width < 8 || height < 8 || pixels.size < width * height) return false
        val edge = maxOf(2, minOf(width, height) / 16)
        var count = 0L
        var red = 0.0
        var green = 0.0
        var blue = 0.0
        for (y in 0 until height) {
            val onEdgeRow = y < edge || y >= height - edge
            for (x in 0 until width) {
                if (!onEdgeRow && x >= edge && x < width - edge) continue
                val pixel = pixels[y * width + x]
                red += (pixel shr 16) and 0xFF
                green += (pixel shr 8) and 0xFF
                blue += pixel and 0xFF
                count++
            }
        }
        if (count == 0L) return false
        val meanRed = red / count
        val meanGreen = green / count
        val meanBlue = blue / count
        var spread = 0.0
        for (y in 0 until height) {
            val onEdgeRow = y < edge || y >= height - edge
            for (x in 0 until width) {
                if (!onEdgeRow && x >= edge && x < width - edge) continue
                val pixel = pixels[y * width + x]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                spread += maxOf(abs(r - meanRed), abs(g - meanGreen), abs(b - meanBlue))
            }
        }
        return spread / count <= PLAIN_BORDER
    }

    /**
     * The whole question, asked of a downloaded file: a shape a bottle photograph has, and a plain
     * field behind it. A file whose format cannot be read is not passed — an unreadable picture is
     * not one to keep.
     */
    fun acceptable(bytes: ByteArray): Boolean {
        val (width, height) = size(bytes) ?: return false
        if (!plausible(width, height)) return false
        val options = BitmapFactory.Options().apply {
            inSampleSize = Integer.highestOneBit(maxOf(1, minOf(width, height) / 96))
                .coerceAtLeast(1)
        }
        val bitmap = runCatching {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        }.getOrNull() ?: return false
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        bitmap.recycle()
        return plainBorder(pixels, bitmap.width, bitmap.height)
    }

    private val JPEG_FRAMES = setOf(
        0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF
    )

    private fun be32(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) shl 24) or ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or (bytes[at + 3].toInt() and 0xFF)

    private fun be16(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)

    private fun le16(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private fun le24(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16)
}