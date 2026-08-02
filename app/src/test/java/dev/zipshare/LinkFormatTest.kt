package dev.zipshare

import dev.zipshare.data.model.LinkFormat
import dev.zipshare.data.model.formatLink
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A markdown link that breaks on paste is worse than no markdown at all - it looks fine in the
 * clipboard and wrong in the message. Both halves are escaped, and these pin the cases that
 * actually occur with real filenames.
 */
class LinkFormatTest {

    @Test
    fun `plain is the url untouched`() {
        assertEquals(
            "https://z.example.com/u/a.png",
            formatLink("a.png", "https://z.example.com/u/a.png", LinkFormat.PLAIN),
        )
    }

    @Test
    fun `markdown shows the name and hides the url`() {
        assertEquals(
            "[shot.png](https://z.example.com/u/shot.png)",
            formatLink("shot.png", "https://z.example.com/u/shot.png", LinkFormat.MARKDOWN),
        )
    }

    @Test
    fun `brackets in the name cannot close the label early`() {
        assertEquals(
            "[a\\[1\\].png](https://z.example.com/u/x.png)",
            formatLink("a[1].png", "https://z.example.com/u/x.png", LinkFormat.MARKDOWN),
        )
    }

    @Test
    fun `a url with parentheses is wrapped so it cannot end the target early`() {
        assertEquals(
            "[my file (2).png](<https://z.example.com/u/my file (2).png>)",
            formatLink(
                "my file (2).png",
                "https://z.example.com/u/my file (2).png",
                LinkFormat.MARKDOWN,
            ),
        )
    }

    @Test
    fun `angle brackets inside the url are encoded rather than closing the wrapper`() {
        assertEquals(
            "[x.png](<https://z.example.com/u/a%3Cb%3E (1).png>)",
            formatLink("x.png", "https://z.example.com/u/a<b> (1).png", LinkFormat.MARKDOWN),
        )
    }

    @Test
    fun `a backslash in the name is escaped before the brackets are`() {
        assertEquals(
            "[a\\\\b.png](https://z.example.com/u/x.png)",
            formatLink("a\\b.png", "https://z.example.com/u/x.png", LinkFormat.MARKDOWN),
        )
    }

    // --- view pages ---

    @Test
    fun `view swaps the raw route for the view route`() {
        assertEquals(
            "https://z.example.com/view/bEa.png",
            formatLink("bEa.png", "https://z.example.com/u/bEa.png", LinkFormat.VIEW),
        )
    }

    @Test
    fun `a sub-path install keeps its prefix`() {
        assertEquals(
            "https://example.com/zipline/view/a.png",
            formatLink("a.png", "https://example.com/zipline/u/a.png", LinkFormat.VIEW),
        )
    }

    @Test
    fun `a custom raw route is handled by position, not by matching slash-u`() {
        // files.route is configurable server-side, so the segment is not always called "u".
        assertEquals(
            "https://z.example.com/view/a.png",
            formatLink("a.png", "https://z.example.com/files/a.png", LinkFormat.VIEW),
        )
    }

    @Test
    fun `a url with no route segment still produces a view path`() {
        assertEquals(
            "https://z.example.com/view/a.png",
            formatLink("a.png", "https://z.example.com/a.png", LinkFormat.VIEW),
        )
    }

    @Test
    fun `a non-default port survives the rewrite`() {
        assertEquals(
            "http://10.0.2.2:8099/view/notes.md",
            formatLink("notes.md", "http://10.0.2.2:8099/u/notes.md", LinkFormat.VIEW),
        )
    }
}
