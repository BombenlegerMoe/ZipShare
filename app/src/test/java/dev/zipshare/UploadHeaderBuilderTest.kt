package dev.zipshare

import dev.zipshare.data.model.CompressionType
import dev.zipshare.data.model.NameFormat
import dev.zipshare.data.model.UploadOptions
import dev.zipshare.data.net.UploadHeaderBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadHeaderBuilderTest {

    @Test
    fun `unset options emit no headers at all`() {
        assertTrue(UploadHeaderBuilder.build(UploadOptions.DEFAULT).isEmpty())
    }

    @Test
    fun `blank strings are omitted, never sent as empty values`() {
        val headers = UploadHeaderBuilder.build(
            UploadOptions(
                deletesAt = "   ",
                password = "",
                filename = "",
                domain = " , ,",
                fileExtension = "",
                folderId = "",
            ),
        )
        assertTrue("expected no headers, got $headers", headers.isEmpty())
        assertFalse(headers.values.any { it.isEmpty() })
    }

    @Test
    fun `every option maps to its pinned header name`() {
        val headers = UploadHeaderBuilder.build(
            UploadOptions(
                deletesAt = "1d",
                format = NameFormat.GFYCAT,
                compressionPercent = 60,
                compressionType = CompressionType.WEBP,
                password = "hunter2",
                maxViews = 3,
                originalName = true,
                folderId = "fld_1",
                filename = "report",
                domain = "a.example.com, b.example.com",
                fileExtension = ".png",
            ),
        )

        assertEquals("1d", headers["x-zipline-deletes-at"])
        assertEquals("gfycat", headers["x-zipline-format"])
        assertEquals("60", headers["x-zipline-image-compression-percent"])
        assertEquals("webp", headers["x-zipline-image-compression-type"])
        assertEquals("hunter2", headers["x-zipline-password"])
        assertEquals("3", headers["x-zipline-max-views"])
        assertEquals("true", headers["x-zipline-original-name"])
        assertEquals("fld_1", headers["x-zipline-folder"])
        assertEquals("report", headers["x-zipline-filename"])
        assertEquals("a.example.com,b.example.com", headers["x-zipline-domain"])
        assertEquals("png", headers["x-zipline-file-extension"])
        assertEquals(11, headers.size)
    }

    @Test
    fun `no-json is never sent`() {
        val headers = UploadHeaderBuilder.build(UploadOptions(format = NameFormat.RANDOM))
        assertNull(headers["x-zipline-no-json"])
    }

    @Test
    fun `originalName false omits the header instead of sending false`() {
        assertNull(
            UploadHeaderBuilder.build(UploadOptions(originalName = false))["x-zipline-original-name"],
        )
        assertEquals(
            "true",
            UploadHeaderBuilder.build(UploadOptions(originalName = true))["x-zipline-original-name"],
        )
    }

    @Test
    fun `compression percent is clamped into 0-100`() {
        assertEquals(
            "100",
            UploadHeaderBuilder.build(
                UploadOptions(compressionPercent = 400),
            )["x-zipline-image-compression-percent"],
        )
        assertEquals(
            "0",
            UploadHeaderBuilder.build(
                UploadOptions(compressionPercent = -5),
            )["x-zipline-image-compression-percent"],
        )
        assertEquals(
            "0",
            UploadHeaderBuilder.build(
                UploadOptions(compressionPercent = 0),
            )["x-zipline-image-compression-percent"],
        )
    }

    @Test
    fun `negative max views is dropped rather than sent`() {
        assertNull(UploadHeaderBuilder.build(UploadOptions(maxViews = -1))["x-zipline-max-views"])
        assertEquals("0", UploadHeaderBuilder.build(UploadOptions(maxViews = 0))["x-zipline-max-views"])
    }

    @Test(expected = IllegalArgumentException::class)
    fun `non-ascii header value is rejected with a message instead of crashing OkHttp`() {
        UploadHeaderBuilder.build(UploadOptions(filename = "Bericht-Größe"))
    }

    @Test
    fun `partial headers match the pinned wire format`() {
        val headers = UploadHeaderBuilder.partial(
            filename = "my holiday.mp4",
            contentType = "video/mp4",
            totalLength = 300,
            start = 100,
            end = 200,
            lastChunk = false,
            identifier = "abc12345",
        )
        assertEquals("bytes 100-200/300", headers["content-range"])
        assertEquals("my%20holiday.mp4", headers["x-zipline-p-filename"])
        assertEquals("video/mp4", headers["x-zipline-p-content-type"])
        assertEquals("300", headers["x-zipline-p-content-length"])
        assertEquals("false", headers["x-zipline-p-lastchunk"])
        assertEquals("abc12345", headers["x-zipline-p-identifier"])
    }

    @Test
    fun `first chunk omits the identifier the server has not issued yet`() {
        val headers = UploadHeaderBuilder.partial(
            "a.bin", "application/octet-stream", 10, 0, 10, true, null,
        )
        assertNull(headers["x-zipline-p-identifier"])
        assertEquals("true", headers["x-zipline-p-lastchunk"])
        assertEquals("bytes 0-10/10", headers["content-range"])
    }

    @Test(expected = IllegalArgumentException::class)
    fun `chunk end beyond total is rejected before it reaches the server`() {
        UploadHeaderBuilder.partial("a.bin", "application/octet-stream", 10, 5, 11, true, "id")
    }

    // --- auto compression ---

    /**
     * "auto" is a UI concept, not a wire value. If it ever reached the server verbatim Zipline
     * would reject the upload, so these pin that it is always substituted or dropped.
     */
    @Test
    fun `auto compression keeps each image in the format it arrived as`() {
        val o = UploadOptions(compressionType = CompressionType.AUTO, compressionPercent = 70)
        assertEquals("jpg", UploadHeaderBuilder.build(o, "image/jpeg")["x-zipline-image-compression-type"])
        assertEquals("png", UploadHeaderBuilder.build(o, "image/png")["x-zipline-image-compression-type"])
        assertEquals("webp", UploadHeaderBuilder.build(o, "image/webp")["x-zipline-image-compression-type"])
        assertEquals("jxl", UploadHeaderBuilder.build(o, "image/jxl")["x-zipline-image-compression-type"])
        assertEquals("70", UploadHeaderBuilder.build(o, "image/png")["x-zipline-image-compression-percent"])
    }

    @Test
    fun `auto sends no compression headers for a file the server cannot re-encode`() {
        val o = UploadOptions(compressionType = CompressionType.AUTO, compressionPercent = 70)
        listOf("video/mp4", "image/gif", "application/pdf", "image/svg+xml", null).forEach { mime ->
            val h = UploadHeaderBuilder.build(o, mime)
            assertNull("type sent for $mime", h["x-zipline-image-compression-type"])
            // The percent must go too: on its own it would let the server pick the format,
            // which is the opposite of what "keep the same format" promises.
            assertNull("percent sent for $mime", h["x-zipline-image-compression-percent"])
        }
    }

    @Test
    fun `the literal string auto never reaches the wire`() {
        val h = UploadHeaderBuilder.build(
            UploadOptions(compressionType = CompressionType.AUTO, compressionPercent = 50),
            "image/jpeg",
        )
        assertFalse(h.values.any { it == "auto" })
    }

    @Test
    fun `an explicitly chosen format is not overridden by the file type`() {
        val h = UploadHeaderBuilder.build(
            UploadOptions(compressionType = CompressionType.WEBP, compressionPercent = 50),
            "image/jpeg",
        )
        assertEquals("webp", h["x-zipline-image-compression-type"])
        assertEquals("50", h["x-zipline-image-compression-percent"])
    }

    // --- per-format auto quality ---

    /**
     * The whole point of splitting the percent: sharp treats it as a lossy quality for JPEG but
     * PNG is lossless, so one shared number cannot suit both.
     */
    @Test
    fun `auto uses the quality belonging to the resolved format`() {
        val o = UploadOptions(
            compressionType = CompressionType.AUTO,
            autoPercentJpg = 70,
            autoPercentPng = 95,
        )
        UploadHeaderBuilder.build(o, "image/jpeg").let {
            assertEquals("jpg", it["x-zipline-image-compression-type"])
            assertEquals("70", it["x-zipline-image-compression-percent"])
        }
        UploadHeaderBuilder.build(o, "image/png").let {
            assertEquals("png", it["x-zipline-image-compression-type"])
            assertEquals("95", it["x-zipline-image-compression-percent"])
        }
    }

    @Test
    fun `webp and jxl follow the jpeg quality, being lossy too`() {
        val o = UploadOptions(
            compressionType = CompressionType.AUTO,
            autoPercentJpg = 70,
            autoPercentPng = 95,
        )
        assertEquals("70", UploadHeaderBuilder.build(o, "image/webp")["x-zipline-image-compression-percent"])
        assertEquals("70", UploadHeaderBuilder.build(o, "image/jxl")["x-zipline-image-compression-percent"])
    }

    @Test
    fun `a format left blank is not compressed at all`() {
        // Falling through to the server's default would compress harder than was asked for.
        val o = UploadOptions(compressionType = CompressionType.AUTO, autoPercentJpg = 70)
        val png = UploadHeaderBuilder.build(o, "image/png")
        assertNull(png["x-zipline-image-compression-type"])
        assertNull(png["x-zipline-image-compression-percent"])
    }

    @Test
    fun `auto falls back to the shared percent when no per-format value is set`() {
        val o = UploadOptions(compressionType = CompressionType.AUTO, compressionPercent = 50)
        assertEquals("50", UploadHeaderBuilder.build(o, "image/png")["x-zipline-image-compression-percent"])
        assertEquals("50", UploadHeaderBuilder.build(o, "image/jpeg")["x-zipline-image-compression-percent"])
    }

    @Test
    fun `an explicit format still uses the single percent, not the auto pair`() {
        val o = UploadOptions(
            compressionType = CompressionType.WEBP,
            compressionPercent = 40,
            autoPercentJpg = 70,
            autoPercentPng = 95,
        )
        val h = UploadHeaderBuilder.build(o, "image/png")
        assertEquals("webp", h["x-zipline-image-compression-type"])
        assertEquals("40", h["x-zipline-image-compression-percent"])
    }
}
