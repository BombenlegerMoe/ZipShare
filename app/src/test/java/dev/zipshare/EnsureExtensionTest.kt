package dev.zipshare

import dev.zipshare.upload.UploadInput
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A gallery share can hand over a display name with no extension. Uploading that produced an
 * extensionless application/octet-stream file, which Zipline then served as a download instead of
 * an image - "it uploads but the picture isn't viewable".
 */
class EnsureExtensionTest {

    @Test
    fun `appends the extension when the name has none`() {
        assertEquals("1000000033.jpg", UploadInput.ensureExtension("1000000033", "jpg"))
        assertEquals("IMG_20260729.png", UploadInput.ensureExtension("IMG_20260729", "png"))
    }

    @Test
    fun `leaves an existing matching extension alone`() {
        assertEquals("holiday.png", UploadInput.ensureExtension("holiday.png", "png"))
        assertEquals("HOLIDAY.PNG", UploadInput.ensureExtension("HOLIDAY.PNG", "png"))
    }

    @Test
    fun `an equivalent extension for the same type is accepted, so no jpeg-dot-jpg`() {
        // MimeTypeMap maps image/jpeg to "jpg", but a file called .jpeg is already correct.
        val jpegIsSameType = { ext: String -> ext == "jpeg" }
        assertEquals(
            "holiday.jpeg",
            UploadInput.ensureExtension("holiday.jpeg", "jpg", jpegIsSameType),
        )
    }

    @Test
    fun `a word after a dot is not mistaken for an extension`() {
        // The earlier length-based heuristic read "photo" as an extension and skipped the fix.
        assertEquals("my.holiday.photo.jpg", UploadInput.ensureExtension("my.holiday.photo", "jpg"))
        assertEquals(
            "screenshot.original.png",
            UploadInput.ensureExtension("screenshot.original", "png"),
        )
    }

    @Test
    fun `unknown mime leaves the name untouched rather than inventing an extension`() {
        assertEquals("mystery", UploadInput.ensureExtension("mystery", null))
        assertEquals("mystery", UploadInput.ensureExtension("mystery", ""))
    }

    @Test
    fun `trailing dot is not treated as an existing extension`() {
        assertEquals("weird..jpg", UploadInput.ensureExtension("weird.", "jpg"))
    }
}
