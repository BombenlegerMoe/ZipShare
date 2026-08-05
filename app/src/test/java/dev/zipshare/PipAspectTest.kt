package dev.zipshare

import dev.zipshare.ui.viewer.pipAspect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android rejects a picture-in-picture aspect ratio outside roughly 1:2.39 .. 2.39:1 by throwing
 * IllegalArgumentException, which would take the viewer down with it. Every result here has to sit
 * inside that window.
 */
class PipAspectTest {

    private val max = 2.39f

    private fun assertAccepted(w: Int, h: Int) {
        val (n, d) = pipAspect(w, h)
        val ratio = n.toFloat() / d
        assertTrue("$n:$d is $ratio, outside what PiP accepts", ratio in (1 / max)..max)
    }

    @Test
    fun `an ordinary landscape video passes through untouched`() {
        assertEquals(1920 to 1080, pipAspect(1920, 1080))
        assertAccepted(1920, 1080)
    }

    @Test
    fun `a portrait phone video passes through untouched`() {
        assertEquals(1080 to 1920, pipAspect(1080, 1920))
        assertAccepted(1080, 1920)
    }

    /** A cinemascope or scrolling-banner clip is wider than PiP allows. */
    @Test
    fun `an extremely wide video is clamped`() {
        assertEquals(239 to 100, pipAspect(4000, 500))
        assertAccepted(4000, 500)
    }

    @Test
    fun `an extremely tall video is clamped`() {
        assertEquals(100 to 239, pipAspect(500, 4000))
        assertAccepted(500, 4000)
    }

    /** videoSize is 0x0 until the first frame is decoded, and the button can be tapped before then. */
    @Test
    fun `an unknown size falls back to 16 by 9`() {
        assertEquals(16 to 9, pipAspect(0, 0))
        assertEquals(16 to 9, pipAspect(-1, 720))
        assertAccepted(0, 0)
    }

    /** Exactly on the boundary must not be clamped, and must not be rejected either. */
    @Test
    fun `the boundary ratio is accepted`() {
        assertAccepted(239, 100)
        assertAccepted(100, 239)
    }
}
