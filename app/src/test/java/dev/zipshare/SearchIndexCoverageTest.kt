package dev.zipshare

import dev.zipshare.ui.Routes
import dev.zipshare.ui.search.appSearchIndex
import dev.zipshare.ui.search.searchEntries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The search index is hand-written, so nothing but this test stops a new row from shipping
 * unreachable. That has already happened twice - the file extension override, then a batch of
 * file actions - which is what makes a reviewer's eye the wrong mechanism for it.
 *
 * Reading the sources rather than the compiled UI is deliberate: a [dev.zipshare.ui.FocusTarget]
 * id only exists as a literal at its call site, and the alternative is instrumenting every screen.
 */
class SearchIndexCoverageTest {

    private val sourceRoot = File("src/main/java")

    @Test
    fun `every anchored row in the UI is reachable from search`() {
        val inUi = focusTargetIds()
        // Instance settings anchor on a key that arrives as JSON, so their FocusTarget takes a
        // variable and no literal exists to scan for. Their correctness is the round trip in
        // `instance settings are findable`, not this scan.
        val indexed = appSearchIndex
            .filterNot { it.route == Routes.ADMIN_SETTINGS }
            .mapNotNull { it.anchor }
            .toSortedSet()

        assertEquals(
            "settings rows that exist but search cannot reach - add a SearchEntry with this anchor",
            emptySet<String>(),
            (inUi - indexed).toSet(),
        )
        assertEquals(
            "index entries whose row is gone - search would land on a page and highlight nothing",
            emptySet<String>(),
            (indexed - inUi).toSet(),
        )
    }

    /**
     * Sub-toggles inside an anchored section have no anchor of their own, so the anchor check
     * above is blind to them - "Show mimetype" shipped unsearchable for exactly that reason.
     * Typing the words printed on the switch has to return *something*.
     */
    @Test
    fun `every sub-toggle is findable by the words printed on it`() {
        val toggles = VIEW_TOGGLE.findAll(sourceText()).map { it.groupValues[1] }.toSortedSet()
        assertTrue("found only ${toggles.size} toggles; the regex has stopped matching", toggles.size >= 6)

        val unfindable = toggles.filter { label ->
            // The leading verb is chrome; people search for the noun.
            val query = label.removePrefix("Show ").removePrefix("Enable ").removePrefix("Disable ")
            searchEntries(query, isAdmin = true).isEmpty()
        }
        assertEquals("toggles that search cannot find", emptyList<String>(), unfindable)
    }

    /**
     * The admin form's rows arrive as JSON at runtime, so neither scan above can see them and no
     * amount of source reading will. "Assume mimetypes" shipped unsearchable behind exactly that.
     */
    @Test
    fun `instance settings are findable by the words on the row`() {
        fun titles(q: String) = searchEntries(q, isAdmin = true).map { it.title }
        assertTrue("mimetypes", titles("mimetypes").contains("Assume mimetypes"))
        assertTrue("raw key", titles("filesassumemimetypes").contains("Assume mimetypes"))
        assertTrue("extensions", titles("disabled extensions").contains("Disabled extensions"))
        assertTrue("gps", titles("gps").contains("Remove gps metadata"))
        assertTrue("ratelimit", titles("ratelimit").isNotEmpty())
        assertTrue("hidden from non-admins", searchEntries("mimetypes", isAdmin = false).isEmpty())
    }

    @Test
    fun `the actions added for file management are findable`() {
        fun titles(q: String) = searchEntries(q, isAdmin = true).map { it.title }
        assertTrue(titles("favourite").contains("Favourites"))
        assertTrue(titles("favorite").contains("Favourites"))
        assertTrue(titles("bulk").contains("Select and move files"))
        assertTrue(titles("tag").contains("Tags"))
        assertTrue(titles("logout").contains("Sign out"))
        assertTrue(titles("cleartext").contains("Allow cleartext (http://)"))
        assertTrue(titles("register").contains("Sign in with an invite"))
    }

    private fun sourceText(): String {
        assertTrue(
            "sources not found - unit tests ran from ${File(".").absolutePath}",
            sourceRoot.isDirectory,
        )
        return sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }
    }

    private fun focusTargetIds(): Set<String> {
        // Without this the regex would find nothing, both sets would be empty, and the test above
        // would pass by being blind rather than by being satisfied.
        assertTrue(
            "sources not found - unit tests ran from ${File(".").absolutePath}",
            sourceRoot.isDirectory,
        )
        val ids = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { FOCUS_TARGET.findAll(it.readText()) }
            .map { it.groupValues[1] }
            .toSortedSet()
        assertTrue("found only ${ids.size} anchored rows; the regex has stopped matching", ids.size > 20)
        return ids
    }

    private companion object {
        /** Matches both `FocusTarget("theme"` and `FocusTarget(id = "theme"`. */
        val FOCUS_TARGET = Regex("""FocusTarget\(\s*(?:id\s*=\s*)?"([a-z_]+)"""")

        /** `ViewToggle("Show mimetype", ...)` - a switch nested inside an anchored section. */
        val VIEW_TOGGLE = Regex("""ViewToggle\("([^"]+)"""")
    }
}
