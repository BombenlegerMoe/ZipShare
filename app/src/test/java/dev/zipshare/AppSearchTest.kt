package dev.zipshare

import dev.zipshare.ui.search.appSearchIndex
import dev.zipshare.ui.search.searchEntries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSearchTest {

    private fun titles(query: String, isAdmin: Boolean = true) =
        searchEntries(query, isAdmin).map { it.title }

    @Test
    fun `an exact screen name ranks above a setting that merely mentions it`() {
        // "Show file types table" contains "file" too; the destination has to win.
        assertEquals("Files", titles("files").first())
    }

    @Test
    fun `a prefix match beats a contains match`() {
        val results = titles("up")
        assertTrue(results.first().startsWith("Up"))
    }

    @Test
    fun `settings are findable by what people actually type, not their label`() {
        assertTrue("dark mode -> Theme", titles("dark mode").contains("Theme"))
        assertTrue("2fa -> two-factor", titles("2fa").contains("Two-factor authentication"))
        assertTrue("fingerprint -> app lock", titles("fingerprint").contains("App lock"))
        assertTrue("markdown -> sharing", titles("markdown").contains("Sharing link format"))
    }

    /** The point of the feature: a setting buried in a screen is reachable by its own name. */
    @Test
    fun `a buried setting is findable`() {
        assertTrue(titles("chunk").contains("Chunked upload"))
        assertTrue(titles("compression").contains("Image compression"))
        assertTrue(titles("sessions").contains("Logged-in devices"))
    }

    @Test
    fun `admin entries are hidden from non-admins`() {
        val asUser = titles("server", isAdmin = false)
        assertFalse("server settings leaked to a non-admin", asUser.contains("Server settings"))
        assertFalse("server actions leaked to a non-admin", asUser.contains("Server actions"))
        assertTrue("the user's own servers page must still be there", asUser.contains("Servers"))
    }

    @Test
    fun `admin entries are visible to admins`() {
        assertTrue(titles("server").contains("Server settings"))
    }

    @Test
    fun `an empty query lists everything visible`() {
        assertEquals(appSearchIndex.size, searchEntries("", isAdmin = true).size)
        assertTrue(searchEntries("", isAdmin = false).size < appSearchIndex.size)
    }

    @Test
    fun `search is case and whitespace insensitive`() {
        assertEquals(titles("metrics"), titles("  METRICS "))
    }

    @Test
    fun `nonsense matches nothing rather than everything`() {
        assertTrue(titles("qqqzzz").isEmpty())
    }

    @Test
    fun `every entry points at a non-blank route`() {
        assertTrue(appSearchIndex.all { it.route.isNotBlank() })
    }
}
