package dev.zipshare

import dev.zipshare.data.model.Profile
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Profiles are persisted as JSON in EncryptedSharedPreferences, so anyone upgrading still has
 * `defaultFolderId` on disk after that field was removed. Decoding must not throw, or the user
 * silently loses every configured server.
 */
class ProfileMigrationTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `profile json written by an older build still decodes`() {
        val stored = """
            [{"id":"abc","label":"Home","baseUrl":"https://zip.example.com","token":"t0ken",
              "pinnedSpkiSha256":null,"defaultFolderId":"fld_123","allowCleartext":false,
              "authenticated":true}]
        """.trimIndent()

        val profiles = json.decodeFromString(ListSerializer(Profile.serializer()), stored)

        assertEquals(1, profiles.size)
        assertEquals("Home", profiles[0].label)
        assertEquals("https://zip.example.com", profiles[0].baseUrl)
        assertEquals("t0ken", profiles[0].token)
    }

    @Test
    fun `unknown future fields are tolerated too`() {
        val stored = """[{"id":"a","label":"L","baseUrl":"https://x.test","token":"t",
            "somethingAddedLater":42,"defaultFolderId":"x"}]"""

        val profiles = json.decodeFromString(ListSerializer(Profile.serializer()), stored)

        assertEquals(1, profiles.size)
        assertNull(profiles[0].pinnedSpkiSha256)
    }
}
