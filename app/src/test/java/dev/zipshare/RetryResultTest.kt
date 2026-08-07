package dev.zipshare

import androidx.work.ListenableWorker.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [dev.zipshare.upload.UploadWorker] decides whether to delete the staged copy and the stashed
 * upload password by asking "was this a retry?". That used to be `is Result.Retry`, but that type
 * is restricted to WorkManager's own library group and lint rejects it, so it now compares against
 * `Result.retry()` by value.
 *
 * Those are only interchangeable if Retry has no state and implements equality - if it did not,
 * every ending would look like a retry and the staged file and password would be kept on disk
 * forever. That is what these pin.
 */
class RetryResultTest {

    @Test
    fun `two retries are equal, so value comparison stands in for a type check`() {
        assertEquals(Result.retry(), Result.retry())
    }

    @Test
    fun `success and failure are not retries`() {
        assertNotEquals(Result.retry(), Result.success())
        assertNotEquals(Result.retry(), Result.failure())
    }

    /** Cancellation reaches the same branch with a null outcome, which must count as "not a retry". */
    @Test
    fun `null is not a retry`() {
        val outcome: Result? = null
        assertTrue("null must take the cleanup branch", outcome != Result.retry())
    }
}
