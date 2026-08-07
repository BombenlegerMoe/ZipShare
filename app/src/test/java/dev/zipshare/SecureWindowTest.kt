package dev.zipshare

import dev.zipshare.ui.SecureWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The flag must survive overlapping holders: the lock screen composes over whatever was open, and
 * that may itself be showing a token or a two-factor QR.
 *
 * Checked on device, unlocking currently returns to Home, so the screen underneath is already gone
 * when the lock screen disposes and the un-counted version happened to behave. These tests pin the
 * invariant rather than that coincidence - the flag stays set while *any* holder remains.
 */
class SecureWindowTest {

    @Before
    fun reset() = SecureWindow.reset()

    @Test
    fun `a single holder clears the flag when it leaves`() {
        SecureWindow.acquire()
        assertTrue("the only holder must clear", SecureWindow.release())
    }

    @Test
    fun `the first of two holders to leave does not clear the flag`() {
        SecureWindow.acquire() // server editor
        SecureWindow.acquire() // lock screen over it
        assertFalse("lock screen left, editor still shows a token", SecureWindow.release())
        assertTrue("editor left too, now it can clear", SecureWindow.release())
    }

    @Test
    fun `nesting three deep still clears exactly once`() {
        repeat(3) { SecureWindow.acquire() }
        assertFalse(SecureWindow.release())
        assertFalse(SecureWindow.release())
        assertTrue(SecureWindow.release())
    }

    /**
     * A release with nothing held must not push the count negative, or the next acquire would not
     * register as the first and the flag would never be set.
     */
    @Test
    fun `an unbalanced release cannot drive the count below zero`() {
        assertTrue(SecureWindow.release())
        assertEquals(0, SecureWindow.activeCount())
        SecureWindow.acquire()
        assertEquals(1, SecureWindow.activeCount())
        assertTrue("the next holder must still be able to clear", SecureWindow.release())
    }

    @Test
    fun `acquire and release balance back to zero`() {
        repeat(5) { SecureWindow.acquire() }
        repeat(5) { SecureWindow.release() }
        assertEquals(0, SecureWindow.activeCount())
    }
}
