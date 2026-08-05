package dev.zipshare

import dev.zipshare.upload.ImageCompressor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageCompressorTest {

    /**
     * The important half of this list is what it excludes. Decoding an animated GIF or WebP through
     * BitmapFactory keeps only the first frame, so "compressing" one silently destroys it.
     */
    @Test
    fun `only still image formats are compressed`() {
        assertTrue(ImageCompressor.canCompress("image/jpeg"))
        assertTrue(ImageCompressor.canCompress("image/png"))
        assertTrue(ImageCompressor.canCompress("image/heic"))
        assertFalse("animated gif would lose every frame but the first", ImageCompressor.canCompress("image/gif"))
        assertFalse("webp may be animated", ImageCompressor.canCompress("image/webp"))
        assertFalse(ImageCompressor.canCompress("video/mp4"))
        assertFalse(ImageCompressor.canCompress("application/pdf"))
        assertFalse(ImageCompressor.canCompress("text/plain"))
    }

    @Test
    fun `mime matching ignores case`() {
        assertTrue(ImageCompressor.canCompress("IMAGE/JPEG"))
    }

    /** Zipline takes the stored extension from the multipart filename, so it has to match. */
    @Test
    fun `the extension is replaced, not appended`() {
        assertEquals("holiday.webp", ImageCompressor.renameForFormat("holiday.jpg", ImageCompressor.WEBP))
        assertEquals("holiday.jpg", ImageCompressor.renameForFormat("holiday.png", ImageCompressor.JPEG))
    }

    @Test
    fun `a name with dots keeps everything but the last segment`() {
        assertEquals("my.holiday.photo.webp", ImageCompressor.renameForFormat("my.holiday.photo.png", ImageCompressor.WEBP))
    }

    @Test
    fun `a name with no extension gains one`() {
        assertEquals("1000062127.webp", ImageCompressor.renameForFormat("1000062127", ImageCompressor.WEBP))
    }

    @Test
    fun `the mime matches the chosen format`() {
        assertEquals("image/webp", ImageCompressor.mimeForFormat(ImageCompressor.WEBP))
        assertEquals("image/jpeg", ImageCompressor.mimeForFormat(ImageCompressor.JPEG))
    }

    @Test
    fun `a real saving is reported as a percentage`() {
        assertEquals(75, ImageCompressor.savingPercent(before = 4_000_000, after = 1_000_000))
        assertEquals(50, ImageCompressor.savingPercent(before = 1000, after = 500))
    }

    /**
     * Re-encoding can produce a *larger* file - a small already-optimised JPEG at a high quality
     * setting is the usual case. Null means "keep the original", so uploading more bytes to save
     * bandwidth cannot happen.
     */
    @Test
    fun `no saving is reported when the result is bigger or equal`() {
        assertNull(ImageCompressor.savingPercent(before = 1000, after = 1200))
        assertNull(ImageCompressor.savingPercent(before = 1000, after = 1000))
    }

    @Test
    fun `an unknown original size cannot claim a saving`() {
        assertNull(ImageCompressor.savingPercent(before = 0, after = 500))
        assertNull(ImageCompressor.savingPercent(before = -1, after = 500))
    }

    /** A saving too small to round to 1% still reads as 1%, never 0%. */
    @Test
    fun `a tiny saving is never reported as zero`() {
        assertEquals(1, ImageCompressor.savingPercent(before = 100_000, after = 99_999))
    }

    // --- decode sizing -------------------------------------------------------------------------
    // An unbounded decode of a 12 MP photo asks for ~48 MB in one block, which is what used to
    // throw OutOfMemoryError and silently skip compression on exactly the photos worth shrinking.

    private val heap256mb = 256L * 1024 * 1024 / 4 // the budget a ~256 MB heap allows

    /** A normal phone photo on a normal heap must decode untouched - no quality change. */
    @Test
    fun `an ordinary photo is not downscaled on a modern heap`() {
        assertEquals(1, ImageCompressor.sampleSizeFor(4032, 3024, heap256mb))
    }

    @Test
    fun `a huge image is downscaled enough to fit the budget`() {
        val sample = ImageCompressor.sampleSizeFor(12000, 9000, heap256mb)
        assertTrue("expected downscaling, got $sample", sample > 1)
        val pixels = (12000L / sample) * (9000L / sample)
        assertTrue("still over budget at 1/$sample", pixels * 4 <= heap256mb)
    }

    /** The same image on a small heap must degrade further rather than fail outright. */
    @Test
    fun `a smaller heap downscales more`() {
        val roomy = ImageCompressor.sampleSizeFor(4032, 3024, heap256mb)
        val tight = ImageCompressor.sampleSizeFor(4032, 3024, 8L * 1024 * 1024)
        assertTrue("tight heap should sample more than $roomy", tight > roomy)
    }

    /** BitmapFactory floors inSampleSize to a power of two, so anything else is wasted precision. */
    @Test
    fun `the sample size is always a power of two`() {
        listOf(heap256mb, 8L * 1024 * 1024, 1L * 1024 * 1024).forEach { budget ->
            val s = ImageCompressor.sampleSizeFor(9000, 7000, budget)
            assertEquals("$s is not a power of two", 0, s and (s - 1))
        }
    }

    @Test
    fun `an unreadable size falls back to no subsampling`() {
        assertEquals(1, ImageCompressor.sampleSizeFor(0, 0, heap256mb))
        assertEquals(1, ImageCompressor.sampleSizeFor(-1, 100, heap256mb))
        assertEquals(1, ImageCompressor.sampleSizeFor(100, 100, 0))
    }
}
