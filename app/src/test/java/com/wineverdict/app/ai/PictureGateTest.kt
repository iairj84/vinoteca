package com.wineverdict.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate a downloaded picture has to pass: one bottle's shape, on a plain field.
 *
 * A shop's own banner is 976×291, a logo strip is 450×1308, a lifestyle pour has a room behind it.
 * Each of those was kept by the first version of the picture finder, so each is a test here.
 */
class PictureGateTest {

    private fun png(width: Int, height: Int): ByteArray {
        val bytes = ByteArray(32)
        byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
            .copyInto(bytes)
        bytes[16] = (width shr 24).toByte()
        bytes[17] = (width shr 16).toByte()
        bytes[18] = (width shr 8).toByte()
        bytes[19] = width.toByte()
        bytes[20] = (height shr 24).toByte()
        bytes[21] = (height shr 16).toByte()
        bytes[22] = (height shr 8).toByte()
        bytes[23] = height.toByte()
        return bytes
    }

    /** SOI, then a frame header that says how big the picture is. */
    private fun jpeg(width: Int, height: Int): ByteArray {
        val bytes = ByteArray(20)
        bytes[0] = 0xFF.toByte()
        bytes[1] = 0xD8.toByte()
        bytes[2] = 0xFF.toByte()
        bytes[3] = 0xC0.toByte()
        bytes[4] = 0x00
        bytes[5] = 0x0B
        bytes[6] = 0x08
        bytes[7] = (height shr 8).toByte()
        bytes[8] = height.toByte()
        bytes[9] = (width shr 8).toByte()
        bytes[10] = width.toByte()
        return bytes
    }

    @Test
    fun aPngsSizeIsReadFromItsHeader() {
        assertEquals(320 to 640, PictureGate.size(png(320, 640)))
    }

    @Test
    fun aJpegsSizeIsReadFromItsFrameHeader() {
        assertEquals(1000 to 1545, PictureGate.size(jpeg(1000, 1545)))
    }

    @Test
    fun somethingThatIsNotAnImageHasNoSize() {
        assertNull(PictureGate.size("<html>not a picture</html>".toByteArray()))
    }

    @Test
    fun aBottlePhotographHasTheShapeOfOne() {
        assertTrue(PictureGate.plausible(1000, 1545))
        assertTrue(PictureGate.plausible(600, 600))
        assertTrue(PictureGate.plausible(700, 400))
    }

    @Test
    fun aShopBannerIsNotABottle() {
        // The one that came back as a photograph of a Chardonnay.
        assertFalse(PictureGate.plausible(976, 291))
    }

    @Test
    fun aStoreLogoStripIsNotABottle() {
        assertFalse(PictureGate.plausible(2756, 765))
        assertFalse(PictureGate.plausible(450, 1308))
    }

    @Test
    fun aThumbnailTooSmallToReadIsNotKept() {
        assertFalse(PictureGate.plausible(148, 173))
        assertFalse(PictureGate.plausible(100, 300))
    }

    @Test
    fun aPlainFieldIsWhatAProductShotHas() {
        val pixels = IntArray(64 * 64) { 0xFFFFFFFF.toInt() }
        assertTrue(PictureGate.plainBorder(pixels, 64, 64))
    }

    @Test
    fun aSceneBehindTheBottleIsNotAPlainField() {
        // A dark room on the left, a lit table on the right: the candlelit pour that was kept.
        val pixels = IntArray(64 * 64) { index ->
            if (index % 64 < 32) 0xFF1A140F.toInt() else 0xFFF2E3C4.toInt()
        }
        assertFalse(PictureGate.plainBorder(pixels, 64, 64))
    }

    @Test
    fun aDarkStudioBackgroundIsStillPlain() {
        val pixels = IntArray(64 * 64) { 0xFF101010.toInt() }
        assertTrue(PictureGate.plainBorder(pixels, 64, 64))
    }

    /** A bottle standing on a plain field: narrow, and nearly the full height. */
    private fun bottle(): IntArray {
        val width = 64
        val height = 96
        return IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            if (x in 26..38 && y in 8..88) 0xFF20301A.toInt() else 0xFFF4F0E6.toInt()
        }
    }

    @Test
    fun aBottleOnAPlainFieldIsKept() {
        val (subjectWidth, subjectHeight) = PictureGate.subjectBox(bottle(), 64, 96)
        assertEquals(0.2f, subjectWidth, 0.05f)
        assertEquals(0.84f, subjectHeight, 0.05f)
        assertTrue(PictureGate.composed(bottle(), 64, 96))
    }

    @Test
    fun aWordmarkBandOnAWhiteCardIsNotABottle() {
        // "International VINE REPORTER": a line of text across a square white card.
        val pixels = IntArray(64 * 64) { index ->
            val x = index % 64
            val y = index / 64
            if (x in 8..56 && y in 27..37) 0xFF1B1B1B.toInt() else 0xFFFFFFFF.toInt()
        }
        assertFalse(PictureGate.composed(pixels, 64, 64))
    }

    @Test
    fun aFullBleedLandscapeHeroIsNotABottle() {
        // A producer's theme photo of the vineyard, filling a landscape frame.
        val width = 96
        val height = 64
        val pixels = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            val shade = 20 * ((x + y) % 2)
            (0xFF - shade) shl 24 or (0x404040) or (shade shl 16) or (shade shl 8)
        }
        assertFalse(PictureGate.composed(pixels, width, height))
    }

    @Test
    fun aLabelFillingAPortraitFrameIsKept() {
        // The Stoller front label: cream, edge to edge, with the field's green only at the trim.
        val pixels = IntArray(64 * 96) { index ->
            val x = index % 64
            val y = index / 64
            if (x in 4..59 && y in 4..91) 0xFFE8DFC6.toInt() else 0xFF31402A.toInt()
        }
        assertTrue(PictureGate.composed(pixels, 64, 96))
    }

    @Test
    fun aTextureIsNotALabel() {
        // A woven or corduroy texture: every neighbour differs from the next.
        val pixels = IntArray(64 * 64) { index ->
            val x = index % 64
            val y = index / 64
            if ((x / 2 + y / 2) % 2 == 0) 0xFF6B5432.toInt() else 0xFF2A2018.toInt()
        }
        assertTrue(PictureGate.busy(pixels, 64, 64) > 0.4f)
        assertFalse(PictureGate.composed(pixels, 64, 64))
    }
}