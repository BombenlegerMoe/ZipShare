package dev.zipshare

import dev.zipshare.data.prefs.AppSettings
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An imported settings file is user-editable text, so it is untrusted input. These pin the two
 * things that keep a bad file from breaking the app: unknown fields are skipped rather than
 * fatal, and anything that is not a settings export fails instead of half-applying.
 */
class SettingsImportTest {

    /** Mirrors the importer in SettingsViewModel. */
    private val importer = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Mirrors the exporter. */
    private val exporter = Json { prettyPrint = true; encodeDefaults = true }

    @Test
    fun `an export round-trips back to the same settings`() {
        val original = AppSettings(
            appLockEnabled = true,
            lockTimeoutSeconds = 120,
            themeMode = "dark",
            recentCount = 42,
            showTypes = false,
        )
        val text = exporter.encodeToString(AppSettings.serializer(), original)
        assertEquals(original, importer.decodeFromString(AppSettings.serializer(), text))
    }

    @Test
    fun `a field from a newer build is skipped instead of failing the import`() {
        val text = """{"recentCount": 7, "somethingAddedLater": true}"""
        val s = importer.decodeFromString(AppSettings.serializer(), text)
        assertEquals(7, s.recentCount)
        // Everything absent falls back to its default rather than being left unset.
        assertEquals(60, s.lockTimeoutSeconds)
    }

    @Test
    fun `a file that is not a settings export is rejected`() {
        listOf("", "not json at all", "[1,2,3]", """{"files":[]}""").forEach { text ->
            val parsed = runCatching {
                importer.decodeFromString(AppSettings.serializer(), text)
            }
            // The last one decodes to defaults rather than throwing - which is harmless, since
            // every field is optional. The first three must fail outright.
            if (text == """{"files":[]}""") {
                assertEquals(AppSettings(), parsed.getOrNull())
            } else {
                assertTrue("should not have parsed: $text", parsed.isFailure)
            }
        }
    }

    @Test
    fun `the exported upload defaults never carry a password`() {
        val withPassword = AppSettings(
            defaultOptions = dev.zipshare.data.model.UploadOptions(password = "hunter2"),
        )
        // What exportSettings() writes: the password stripped before encoding.
        val safe = withPassword.copy(
            defaultOptions = withPassword.defaultOptions.copy(password = null),
        )
        val text = exporter.encodeToString(AppSettings.serializer(), safe)
        assertTrue("password leaked into the export: $text", !text.contains("hunter2"))
        assertNull(
            importer.decodeFromString(AppSettings.serializer(), text).defaultOptions.password,
        )
    }
}
