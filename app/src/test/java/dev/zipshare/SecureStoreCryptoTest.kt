package dev.zipshare

import dev.zipshare.data.prefs.isPermanentLoss
import dev.zipshare.data.prefs.sealAll
import dev.zipshare.data.prefs.seal
import dev.zipshare.data.prefs.unseal
import dev.zipshare.data.prefs.verifyMigrated
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import javax.crypto.KeyGenerator

/**
 * The sealing and migration-check rules, run against a plain JCE key since Keystore is not
 * available on the JVM. The cipher and AAD handling are identical either way.
 */
class SecureStoreCryptoTest {

    private fun newKey() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val key = newKey()

    @Test
    fun `a sealed value opens back to itself`() {
        val token = "MOCKTOKEN123 with unicode ✓"
        assertEquals(token, unseal(key, "profiles_v1", seal(key, "profiles_v1", token)))
    }

    @Test
    fun `sealing twice gives different ciphertexts`() {
        assertNotEquals(seal(key, "k", "same"), seal(key, "k", "same"))
    }

    @Test(expected = Exception::class)
    fun `a value moved under another name does not open`() {
        unseal(key, "active_profile", seal(key, "profiles_v1", "x"))
    }

    @Test(expected = Exception::class)
    fun `a tampered value does not open`() {
        val bytes = Base64.getDecoder().decode(seal(key, "k", "secret"))
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        unseal(key, "k", Base64.getEncoder().encodeToString(bytes))
    }

    @Test(expected = Exception::class)
    fun `another key does not open it`() {
        unseal(newKey(), "k", seal(key, "k", "secret"))
    }

    private val legacy = mapOf(
        "profiles_v1" to "[{\"id\":\"a\",\"token\":\"t\"}]",
        "active_profile" to "a",
        "upload_started_x" to 1_700_000_000_000L,
        "gone" to null,
    )

    @Test
    fun `a full copy verifies, longs included, nulls skipped`() {
        val sealed = sealAll(key, legacy)
        assertEquals(setOf("profiles_v1", "active_profile", "upload_started_x"), sealed.keys)
        assertEquals("1700000000000", unseal(key, "upload_started_x", sealed.getValue("upload_started_x")))
        assertTrue(verifyMigrated(key, legacy, sealed::get))
    }

    @Test
    fun `a missing entry fails verification`() {
        val sealed = sealAll(key, legacy) - "active_profile"
        assertFalse(verifyMigrated(key, legacy, sealed::get))
    }

    @Test
    fun `a wrong value fails verification`() {
        val sealed = sealAll(key, legacy) + ("active_profile" to seal(key, "active_profile", "b"))
        assertFalse(verifyMigrated(key, legacy, sealed::get))
    }

    @Test
    fun `a value that cannot be opened fails verification instead of throwing`() {
        val sealed = sealAll(key, legacy) + ("active_profile" to "not base64 at all!")
        assertFalse(verifyMigrated(key, legacy, sealed::get))
    }

    @Test
    fun `an empty legacy store verifies`() {
        assertTrue(verifyMigrated(key, emptyMap<String, Any>(), sealAll(key, emptyMap<String, Any>())::get))
    }

    private fun failure(block: () -> Unit): Throwable = runCatching(block).exceptionOrNull()!!

    /** These, and only these, may wipe the store: the data can never be read again. */
    @Test
    fun `a wrong key or garbage bytes count as permanent loss`() {
        assertTrue(isPermanentLoss(failure { unseal(newKey(), "k", seal(key, "k", "secret")) }))
        assertTrue(isPermanentLoss(failure { unseal(key, "k", "not base64 !!!") }))
        assertTrue(isPermanentLoss(failure { unseal(key, "k", Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))) }))
    }

    /** A Keystore that is briefly unavailable must leave the profiles alone. */
    @Test
    fun `a transient keystore failure does not`() {
        assertFalse(isPermanentLoss(java.security.KeyStoreException("busy")))
        assertFalse(isPermanentLoss(java.security.ProviderException("keystore2 died")))
        assertFalse(isPermanentLoss(IllegalStateException("not ready")))
    }
}
