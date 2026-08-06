package dev.zipshare

import dev.zipshare.upload.UploadEnqueuer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The queue screen decides which rows belong to the active server purely from these tags, so a
 * change in either half of the round trip silently empties the queue instead of failing loudly.
 */
class UploadProfileTagTest {

    @Test
    fun `profileOf reads back what profileTag wrote`() {
        val id = "3f1c9e2a-0b44-4e77-9a11-5c8d2e6f7a90"
        val tags = setOf(UploadEnqueuer.TAG, "upload:abc", "name:holiday.png", UploadEnqueuer.profileTag(id))
        assertEquals(id, UploadEnqueuer.profileOf(tags))
    }

    /** Work enqueued before uploads carried a profile tag; the queue shows it rather than hiding it. */
    @Test
    fun `profileOf is null when no profile tag is present`() {
        assertNull(UploadEnqueuer.profileOf(setOf(UploadEnqueuer.TAG, "upload:abc", "name:holiday.png")))
    }

    /**
     * "name:" sorts before "profile:" and both are prefixed tags on the same work, so a prefix
     * check that matched loosely would return the file name as the profile id.
     */
    @Test
    fun `profileOf is not confused by the name tag`() {
        val tags = setOf("name:profile:not-an-id.png", UploadEnqueuer.profileTag("real-id"))
        assertEquals("real-id", UploadEnqueuer.profileOf(tags))
    }
}
