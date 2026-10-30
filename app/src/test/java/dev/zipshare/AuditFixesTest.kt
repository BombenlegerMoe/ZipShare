package dev.zipshare

import dev.zipshare.data.net.CertRole
import dev.zipshare.data.net.ChainPin
import dev.zipshare.data.net.withRecommendations
import dev.zipshare.di.sendsCredentials
import dev.zipshare.security.shouldLock
import dev.zipshare.ui.settings.readImport
import dev.zipshare.upload.passwordWasLost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rules from the 2026-10 audit; each one guards a way a token or a protected file could leak. */
class AuditFixesTest {

    /** A password-protected upload whose password vanished must not go out unprotected. */
    @Test
    fun `a lost upload password is detected, an upload without one is not`() {
        assertTrue(passwordWasLost(secretId = "abc", password = null))
        assertFalse(passwordWasLost(secretId = "abc", password = "hunter2"))
        assertFalse(passwordWasLost(secretId = null, password = null))
    }

    /** Previews reuse the profile's pinned client only for its own host; nothing else gets the token. */
    @Test
    fun `only the profile's own host is sent credentials`() {
        assertTrue(sendsCredentials("pix.bombenleger.pro", "pix.bombenleger.pro"))
        assertTrue(sendsCredentials("PIX.bombenleger.pro", "pix.bombenleger.pro"))
        assertFalse(sendsCredentials("evil.example", "pix.bombenleger.pro"))
        assertFalse(sendsCredentials("pix.bombenleger.pro.evil.example", "pix.bombenleger.pro"))
        assertFalse(sendsCredentials("pix.bombenleger.pro", null))
    }

    private fun pin(name: String, role: CertRole) = ChainPin(name, "issuer", "sha256/$name", "2030", role)

    /**
     * Let's Encrypt issues from sibling intermediates at random, so the intermediate alone would
     * break at about every other renewal; the whole CA set is recommended, the leaf never.
     */
    @Test
    fun `every CA cert is recommended, the leaf is not`() {
        val chain = withRecommendations(
            listOf(
                pin("leaf", CertRole.LEAF),
                pin("YE2", CertRole.INTERMEDIATE),
                pin("RootYE", CertRole.INTERMEDIATE),
                pin("X2", CertRole.INTERMEDIATE),
            ),
        )
        assertEquals(listOf(false, true, true, true), chain.map { it.recommended })
    }

    @Test
    fun `a lone self-signed cert is the only thing to pin`() {
        assertEquals(listOf(true), withRecommendations(listOf(pin("self", CertRole.LEAF))).map { it.recommended })
    }

    @Test
    fun `the app locks only once the timeout has passed`() {
        assertTrue(shouldLock(enabled = true, backgroundedAt = 1_000, now = 61_000, timeoutSeconds = 60))
        assertFalse(shouldLock(enabled = true, backgroundedAt = 1_000, now = 60_999, timeoutSeconds = 60))
        assertTrue("timeout 0 locks at once", shouldLock(true, 1_000, 1_000, 0))
        assertFalse("never backgrounded", shouldLock(true, null, 61_000, 60))
        assertFalse("lock off", shouldLock(false, 1_000, 999_999, 60))
    }

    @Test
    fun `an import file is read up to its cap and refused beyond it`() {
        assertEquals("{}", readImport("{}".byteInputStream()))
        assertThrows(IllegalStateException::class.java) {
            readImport(java.io.ByteArrayInputStream(ByteArray(1024 * 1024 + 1)))
        }
    }
}
