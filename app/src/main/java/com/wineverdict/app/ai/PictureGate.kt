package com.wineverdict.app.ai

import android.graphics.BitmapFactory
import kotlin.math.abs

/**
 * Whether a downloaded picture is the bottle.
 *
 * The first version took whatever a page declared for itself, and a page's declaration is often its
 * own furniture: a shop's 976×291 "Uncork Life" banner came back as a bottle's photograph, a brand
 * page's candlelit pour for a Pinot Noir, and a producer's cork. The second version added a shape and
 * a plain-field test, which still kept a review site's wordmark card — "International VINE REPORTER"
 * on white — and a producer's full-bleed theme photo of the vineyard rows.
 *
 * Three questions are asked of the bytes now, all read from the file rather than trusted from what
 * the page said about it:
 *
 * - a shape a photograph of one bottle has (not a banner, not a logo strip, not a thumbnail);
 * - a field behind the subject that is one colour (not a room, a table, a glass or a hand);
 * - a subject that spans the height of the frame (a bottle, or a label filling the frame), which a
 *   wordmark or a horizontal band never does, and no full-bleed landscape photo, which is a hero.
 *
 * The thresholds come from measuring ten known-good bottle and label pictures against the junk a
 * cellar actually received: good subjects sat between 0.80 and 1.0 of the frame height, the wordmark
 * band at about 0.2, and the good pictures were between 0.03 and 0.22 busy against a texture's
 * near-1.0. A picture that fails is dropped, and the next candidate on the page is tried.
 */
object PictureGate {

    /** Smallest side worth zooming into: below this a label's text is mush. */
    const val MIN_SIDE = 150

    private const val MIN_RATIO = 0.45f
    private const val MAX_RATIO = 1.9f

    /** Mean brightness spread around the edge a plain background has; a scene is far above. */
    private const val PLAIN_BORDER = 34.0

    /** How far from the border's colour a pixel has to be to count as the subject. */
    private const val SUBJECT_DISTANCE = 40.0

    /** Lower than this and the "subject" is a line of text in a large empty card. */
    private const val MIN_SUBJECT_HEIGHT = 0.5f

    /** Above this the picture is texture — vineyard rows, a woven label, a crowd. */
    private const val MAX_BUSY = 0.40

    /** The pixels a file declares for itself, read from its header. Null when unknown. */
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
        val border = border(pixels, width, height) ?: return false
        return border.spread <= PLAIN_BORDER
    }

    /**
     * The share of the frame's height and width the subject covers, measured against the colour of
     * the field behind it. A bottle fills most of the height; a wordmark is a band across the middle.
     */
    fun subjectBox(pixels: IntArray, width: Int, height: Int): Pair<Float, Float> {
        val border = border(pixels, width, height) ?: return 0f to 0f
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (!isSubject(pixels[y * width + x], border.mean)) continue
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
        if (right < left || bottom < top) return 0f to 0f
        return (right - left + 1).toFloat() / width to (bottom - top + 1).toFloat() / height
    }

    /** The share of the frame where neighbouring pixels differ sharply: texture, not a label. */
    fun busy(pixels: IntArray, width: Int, height: Int): Float {
        if (width < 3 || height < 3 || pixels.size < width * height) return 1f
        var step = 0
        var strong = 0
        // Every pixel, not every other one: a woven texture's stripes and a corduroy's ribs are two
        // pixels apart often enough that a stride of two reads them as flat.
        for (y in 0 until height - 1) {
            for (x in 0 until width - 1) {
                val pixel = pixels[y * width + x]
                step++
                if (contrast(pixel, pixels[y * width + x + 1]) > 60 ||
                    contrast(pixel, pixels[(y + 1) * width + x]) > 60
                ) {
                    strong++
                }
            }
        }
        return if (step == 0) 1f else strong.toFloat() / step
    }

    /**
     * The whole question, asked of a downloaded file: one bottle's shape, a plain field behind it,
     * a subject that spans the height, and no full-bleed landscape hero.
     */
    fun composed(pixels: IntArray, width: Int, height: Int): Boolean {
        if (!plainBorder(pixels, width, height)) return false
        if (busy(pixels, width, height) > MAX_BUSY) return false
        val (subjectWidth, subjectHeight) = subjectBox(pixels, width, height)
        val full = subjectWidth >= 0.9f && subjectHeight >= 0.9f
        // A label photographed edge to edge is all subject and no field; that is a portrait or a
        // square, never the wide strip a hero or a newsletter banner is.
        if (full) return height >= width
        return subjectHeight >= MIN_SUBJECT_HEIGHT
    }

    /** The file's declared shape and the pixels behind it, together. */
    fun acceptable(bytes: ByteArray): Boolean {
        val (declaredWidth, declaredHeight) = size(bytes) ?: return false
        if (!plausible(declaredWidth, declaredHeight)) return false
        val options = BitmapFactory.Options().apply {
            inSampleSize = Integer.highestOneBit(
                maxOf(1, minOf(declaredWidth, declaredHeight) / 96)
            ).coerceAtLeast(1)
        }
        val bitmap = runCatching {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        }.getOrNull() ?: return false
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val width = bitmap.width
        val height = bitmap.height
        bitmap.recycle()
        return composed(pixels, width, height)
    }

    private class Border(val mean: IntArray, val spread: Double)

    /** The border's mean colour and its average distance from it, in one pass. */
    private fun border(pixels: IntArray, width: Int, height: Int): Border? {
        if (width < 8 || height < 8 || pixels.size < width * height) return null
        val edge = maxOf(2, minOf(width, height) / 16)
        var count = 0L
        var red = 0.0
        var green = 0.0
        var blue = 0.0
        forEachBorderPixel(width, height, edge) { x, y ->
            val pixel = pixels[y * width + x]
            red += (pixel shr 16) and 0xFF
            green += (pixel shr 8) and 0xFF
            blue += pixel and 0xFF
            count++
        }
        if (count == 0L) return null
        val mean = intArrayOf(
            (red / count).toInt(),
            (green / count).toInt(),
            (blue / count).toInt()
        )
        var spread = 0.0
        forEachBorderPixel(width, height, edge) { x, y ->
            val pixel = pixels[y * width + x]
            spread += maxOf(
                abs(((pixel shr 16) and 0xFF) - mean[0]),
                abs(((pixel shr 8) and 0xFF) - mean[1]),
                abs((pixel and 0xFF) - mean[2])
            ).toDouble()
        }
        return Border(mean, spread / count)
    }

    private inline fun forEachBorderPixel(
        width: Int,
        height: Int,
        edge: Int,
        action: (Int, Int) -> Unit
    ) {
        for (y in 0 until height) {
            val onEdgeRow = y < edge || y >= height - edge
            for (x in 0 until width) {
                if (!onEdgeRow && x >= edge && x < width - edge) continue
                action(x, y)
            }
        }
    }

    private fun isSubject(pixel: Int, mean: IntArray): Boolean = maxOf(
        abs(((pixel shr 16) and 0xFF) - mean[0]),
        abs(((pixel shr 8) and 0xFF) - mean[1]),
        abs((pixel and 0xFF) - mean[2])
    ) > SUBJECT_DISTANCE

    private fun contrast(first: Int, second: Int): Int =
        abs(((first shr 16) and 0xFF) - ((second shr 16) and 0xFF)) +
            abs(((first shr 8) and 0xFF) - ((second shr 8) and 0xFF)) +
            abs((first and 0xFF) - (second and 0xFF))

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