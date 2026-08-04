package dev.zipshare

import dev.zipshare.ui.viewer.readCapped
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The text viewer used to decide this from `Content-Length`, which is -1 on any chunked response -
 * so `-1 > max` was false and an arbitrarily large body was read into a String anyway. These pin
 * the decision to what actually arrives, which is the only thing that holds for a chunked source.
 */
class ReadCappedTest {

    @Test
    fun `a body under the limit is returned whole`() {
        val source = Buffer().writeUtf8("hello")
        assertEquals("hello", readCapped(source, 1024))
    }

    @Test
    fun `a body exactly on the limit is still accepted`() {
        val text = "x".repeat(64)
        assertEquals(text, readCapped(Buffer().writeUtf8(text), 64))
    }

    @Test
    fun `one byte over the limit is rejected`() {
        val source = Buffer().writeUtf8("x".repeat(65))
        assertNull(readCapped(source, 64))
    }

    /** The case the old contentLength() check let through. */
    @Test
    fun `a source far over the limit is rejected rather than materialised`() {
        val source = Buffer().writeUtf8("x".repeat(200_000))
        assertNull(readCapped(source, 1024))
    }

    @Test
    fun `an empty body is not mistaken for an oversized one`() {
        assertEquals("", readCapped(Buffer(), 1024))
    }

    @Test
    fun `multi-byte characters are counted as bytes, not characters`() {
        // 4 characters, 12 bytes in UTF-8. A character-based limit would wrongly accept this.
        val text = "日本語だ"
        assertEquals(12, Buffer().writeUtf8(text).size)
        assertNull(readCapped(Buffer().writeUtf8(text), 8))
        assertEquals(text, readCapped(Buffer().writeUtf8(text), 12))
    }
}
