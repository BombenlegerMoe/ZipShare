package dev.zipshare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedLinkTest {

    @Test
    fun `a bare url is the link`() {
        assertEquals("https://example.com/a/b", sharedLink("https://example.com/a/b"))
    }

    @Test
    fun `surrounding whitespace is ignored`() {
        assertEquals("https://example.com", sharedLink("  https://example.com \n"))
    }

    /** How X, Reddit and most "share with comment" flows send a link. */
    @Test
    fun `a link wrapped in other words is still found`() {
        assertEquals("https://t.co/abc", sharedLink("Look at this https://t.co/abc so good"))
        assertEquals("https://example.com", sharedLink("Page Title\nhttps://example.com"))
    }

    /**
     * A trailing full stop belongs to the sentence, not the url - shortening it produces a
     * destination that 404s for everyone who follows the link.
     */
    @Test
    fun `sentence punctuation is not part of the link`() {
        assertEquals("https://example.com/page", sharedLink("have a look at https://example.com/page."))
        assertEquals("https://example.com", sharedLink("https://example.com,"))
        assertEquals("https://example.com", sharedLink("\"https://example.com\""))
    }

    /** Wikipedia's disambiguation links really do end in a bracket, so those must survive. */
    @Test
    fun `a bracket the link opened is kept`() {
        assertEquals(
            "https://en.wikipedia.org/wiki/Mercury_(planet)",
            sharedLink("https://en.wikipedia.org/wiki/Mercury_(planet)"),
        )
    }

    /** ...but one it never opened belongs to the sentence. */
    @Test
    fun `a bracket the link did not open is dropped`() {
        assertEquals("https://example.com", sharedLink("see it here (https://example.com)"))
    }

    @Test
    fun `http is accepted, not just https`() {
        assertEquals("http://10.0.0.5:3000/x", sharedLink("http://10.0.0.5:3000/x"))
    }

    /**
     * Two links is a guess about which one was meant, and guessing wrong shortens the wrong thing
     * silently. Falling through costs the user one tap; guessing costs them a junk short link.
     */
    @Test
    fun `two links are refused rather than guessed`() {
        assertNull(sharedLink("https://a.com and https://b.com"))
    }

    @Test
    fun `plain text without a link is not a link`() {
        assertNull(sharedLink("just some notes I wanted to keep"))
        assertNull(sharedLink("example.com"))
        assertNull(sharedLink("ftp://files.example.com/x"))
    }

    @Test
    fun `a scheme with no host is refused`() {
        assertNull(sharedLink("https://"))
        assertNull(sharedLink("https:///nohost"))
    }

    @Test
    fun `nothing shared is not a link`() {
        assertNull(sharedLink(null))
        assertNull(sharedLink(""))
        assertNull(sharedLink("   "))
    }
}
