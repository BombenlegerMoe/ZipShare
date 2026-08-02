package dev.zipshare

import dev.zipshare.ui.browse.BrowseViewModel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The files screen calls setFolderFilter from a LaunchedEffect, which re-runs whenever the
 * composable is recreated - opening a file and pressing back does exactly that. Resetting
 * unconditionally sent the user back to page 1 nearly every time.
 */
class PagingResetTest {

    @Test
    fun `first load always reloads`() {
        assertTrue(BrowseViewModel.shouldReload(loaded = false, current = null, requested = null))
        assertTrue(BrowseViewModel.shouldReload(loaded = false, current = null, requested = "fld1"))
    }

    @Test
    fun `returning to the same folder keeps the current page`() {
        assertFalse(BrowseViewModel.shouldReload(loaded = true, current = null, requested = null))
        assertFalse(BrowseViewModel.shouldReload(loaded = true, current = "fld1", requested = "fld1"))
    }

    @Test
    fun `changing folder reloads from page one`() {
        assertTrue(BrowseViewModel.shouldReload(loaded = true, current = null, requested = "fld1"))
        assertTrue(BrowseViewModel.shouldReload(loaded = true, current = "fld1", requested = null))
        assertTrue(BrowseViewModel.shouldReload(loaded = true, current = "fld1", requested = "fld2"))
    }
}
