package dev.zipshare

import dev.zipshare.upload.isForeignContent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The upload trust boundary: anything another app hands in is read with this app's identity, so
 * only content from someone else's provider may pass - never a raw path, never our own provider.
 */
class ForeignContentTest {

    private val own = setOf("dev.zipshare.fileprovider", "dev.zipshare.androidx-startup")

    @Test
    fun `content from another app's provider is accepted`() {
        assertTrue(isForeignContent("content", "com.android.providers.media.documents", own))
        assertTrue(isForeignContent("content", "media", own))
        assertTrue(isForeignContent("CONTENT", "com.google.android.apps.photos.contentprovider", own))
    }

    /** The attack this guards: any app pointing us at our own private files. */
    @Test
    fun `a raw file path is refused, including our own private data`() {
        assertFalse(isForeignContent("file", null, own))
        assertFalse(isForeignContent("file", "", own))
        assertFalse(isForeignContent("FILE", null, own))
    }

    /** Same attack through the back door: our provider serves the export folder (backups). */
    @Test
    fun `our own providers are refused, whatever the case`() {
        assertFalse(isForeignContent("content", "dev.zipshare.fileprovider", own))
        assertFalse(isForeignContent("content", "DEV.ZipShare.FileProvider", own))
        assertFalse(isForeignContent("content", "dev.zipshare.androidx-startup", own))
    }

    @Test
    fun `anything else is refused`() {
        assertFalse(isForeignContent(null, "media", own))
        assertFalse(isForeignContent("content", null, own))
        assertFalse(isForeignContent("content", " ", own))
        assertFalse(isForeignContent("http", "example.com", own))
        assertFalse(isForeignContent("android.resource", "dev.zipshare", own))
    }
}
