package dev.zipshare

import dev.zipshare.data.prefs.isStaleSecret
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sweep deletes upload passwords, so the cost of the two mistakes is asymmetric: keeping a
 * dead secret leaks it, but deleting a live one fails a running upload with a wrong password.
 * These pin the boundary in both directions.
 */
class StaleSecretTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 1_700_000_000_000L

    @Test
    fun `a secret younger than the limit is kept`() {
        assertFalse(isStaleSecret(startedAt = now - day / 2, now = now, maxAgeMillis = day))
    }

    @Test
    fun `a secret exactly at the limit is still kept`() {
        assertFalse(isStaleSecret(startedAt = now - day, now = now, maxAgeMillis = day))
    }

    @Test
    fun `a secret past the limit is swept`() {
        assertTrue(isStaleSecret(startedAt = now - day - 1, now = now, maxAgeMillis = day))
    }

    /** Written before the sweep existed: nothing is known about it, so it cannot be trusted. */
    @Test
    fun `a secret with no timestamp is swept`() {
        assertTrue(isStaleSecret(startedAt = 0L, now = now, maxAgeMillis = day))
    }

    @Test
    fun `a negative timestamp is treated as missing rather than ancient`() {
        assertTrue(isStaleSecret(startedAt = -1L, now = now, maxAgeMillis = day))
    }

    /**
     * A clock that jumped backwards must not delete a secret an upload is still using - the
     * subtraction goes negative, which must not read as "older than the limit".
     */
    @Test
    fun `a timestamp in the future is kept`() {
        assertFalse(isStaleSecret(startedAt = now + day, now = now, maxAgeMillis = day))
    }
}
