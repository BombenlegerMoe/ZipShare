package dev.zipshare

import dev.zipshare.upload.UploadNotifications
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * A completion notification once vanished a moment after being posted, because it reused the
 * worker's foreground notification id and WorkManager cancels that id when the worker finishes.
 * The two id spaces must stay disjoint.
 */
class NotificationIdTest {

    @Test
    fun `result ids never collide with foreground ids`() {
        // Past one full wrap of the counter, so the reuse point is covered too.
        val results = (1..25_000).map { UploadNotifications.nextResultId() }.toSet()
        results.forEach { assertTrue("result id must be positive: $it", it > 0) }

        // Int.MIN_VALUE included deliberately: abs() cannot negate it, so the property relied on
        // is "never positive", not "always negative".
        val hashes = List(2_000) { UUID.randomUUID().hashCode() } +
            listOf(Int.MIN_VALUE, Int.MAX_VALUE, 0, -1, 900_001)
        hashes.forEach { hash ->
            val fg = UploadNotifications.foregroundId(hash)
            assertTrue("foreground id must not be positive: $fg", fg <= 0)
            assertTrue("foreground id $fg collides with a result id", fg !in results)
        }
    }

    @Test
    fun `consecutive result ids differ so notifications do not overwrite each other`() {
        val batch = (1..10).map { UploadNotifications.nextResultId() }
        assertEquals(batch.size.toLong(), batch.distinct().size.toLong())
    }
}
