package dev.zipshare

import dev.zipshare.data.ProfileBackup
import dev.zipshare.data.model.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * This file is the one artifact that carries API tokens off the device, so the failure modes matter
 * more than the happy path: a wrong password must fail closed, and a tampered file must not decrypt
 * to something that looks plausible.
 */
class ProfileBackupTest {

    private val profiles = listOf(
        Profile(
            id = "p1",
            label = "Home",
            baseUrl = "https://zip.example.com",
            token = "SECRET-TOKEN-1",
            pinnedSpkiSha256 = "sha256/abc123",
            allowCleartext = false,
        ),
        Profile(
            id = "p2",
            label = "LAN box",
            baseUrl = "http://10.0.0.5:3000",
            token = "SECRET-TOKEN-2",
            allowCleartext = true,
            authenticated = false,
        ),
    )

    @Test
    fun `a backup round-trips every field`() {
        val text = ProfileBackup.encrypt(profiles, "correct horse battery staple")
        assertEquals(profiles, ProfileBackup.decrypt(text, "correct horse battery staple"))
    }

    @Test
    fun `the token never appears in the file`() {
        val text = ProfileBackup.encrypt(profiles, "pw")
        assertTrue("token leaked in clear", !text.contains("SECRET-TOKEN-1"))
        assertTrue("token leaked in clear", !text.contains("SECRET-TOKEN-2"))
        assertTrue("url leaked in clear", !text.contains("zip.example.com"))
    }

    @Test
    fun `a wrong password is rejected rather than returning garbage`() {
        val text = ProfileBackup.encrypt(profiles, "right")
        assertThrows(ProfileBackup.WrongPasswordException::class.java) {
            ProfileBackup.decrypt(text, "wrong")
        }
    }

    /** GCM authenticates the ciphertext, so a single flipped character must fail closed. */
    @Test
    fun `a tampered payload is rejected`() {
        val text = ProfileBackup.encrypt(profiles, "pw")
        val payload = Regex("\"data\": \"([^\"]+)\"").find(text)!!.groupValues[1]
        val flipped = payload.let { if (it[5] == 'A') it.replaceRange(5, 6, "B") else it.replaceRange(5, 6, "A") }
        assertThrows(ProfileBackup.WrongPasswordException::class.java) {
            ProfileBackup.decrypt(text.replace(payload, flipped), "pw")
        }
    }

    @Test
    fun `a file that is not a backup is reported as such, not as a bad password`() {
        listOf("", "not json", "[1,2,3]", """{"format":"something-else","version":1}""").forEach {
            assertThrows(
                "should have been rejected: $it",
                ProfileBackup.NotABackupException::class.java,
            ) { ProfileBackup.decrypt(it, "pw") }
        }
    }

    /** A file from a future build could hold fields this one would silently drop on re-export. */
    @Test
    fun `a newer format version is refused`() {
        val text = ProfileBackup.encrypt(profiles, "pw").replace("\"version\": 1", "\"version\": 99")
        assertThrows(ProfileBackup.NotABackupException::class.java) {
            ProfileBackup.decrypt(text, "pw")
        }
    }

    /** Same input twice must not give the same bytes, or the salt and IV are not doing their job. */
    @Test
    fun `two exports of the same profiles differ`() {
        val a = ProfileBackup.encrypt(profiles, "pw")
        val b = ProfileBackup.encrypt(profiles, "pw")
        assertNotEquals(a, b)
        assertEquals(ProfileBackup.decrypt(a, "pw"), ProfileBackup.decrypt(b, "pw"))
    }

    @Test
    fun `an empty profile list is still a valid backup`() {
        val text = ProfileBackup.encrypt(emptyList(), "pw")
        assertEquals(emptyList<Profile>(), ProfileBackup.decrypt(text, "pw"))
    }

    @Test
    fun `re-importing the same backup adds nothing`() {
        assertEquals(emptyList<Profile>(), ProfileBackup.toAdd(profiles, profiles))
    }

    @Test
    fun `importing onto an empty device adds everything`() {
        assertEquals(profiles, ProfileBackup.toAdd(emptyList(), profiles))
    }

    /** Ids are regenerated on import, so a match cannot be decided on them. */
    @Test
    fun `the same server with a different id is still a duplicate`() {
        val renumbered = profiles.map { it.copy(id = "other-${it.id}") }
        assertEquals(emptyList<Profile>(), ProfileBackup.toAdd(profiles, renumbered))
    }

    /** A renamed server is the same server; the label is the field users change most. */
    @Test
    fun `a renamed server is still a duplicate`() {
        val renamed = profiles.map { it.copy(label = "${it.label} (old phone)") }
        assertEquals(emptyList<Profile>(), ProfileBackup.toAdd(profiles, renamed))
    }

    /** A rotated token is a genuinely different credential and has to come through. */
    @Test
    fun `the same address with a new token is added`() {
        val rotated = listOf(profiles[0].copy(token = "ROTATED"))
        assertEquals(rotated, ProfileBackup.toAdd(profiles, rotated))
    }

    @Test
    fun `only the new servers are added from a mixed backup`() {
        val fresh = Profile(id = "p3", label = "Work", baseUrl = "https://work.example", token = "T3")
        assertEquals(listOf(fresh), ProfileBackup.toAdd(profiles, profiles + fresh))
    }

    @Test
    fun `a unicode password works`() {
        val text = ProfileBackup.encrypt(profiles, "pässwörd-日本語-🔐")
        assertEquals(profiles, ProfileBackup.decrypt(text, "pässwörd-日本語-🔐"))
    }
}
