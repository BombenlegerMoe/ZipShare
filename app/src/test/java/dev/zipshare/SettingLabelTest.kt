package dev.zipshare

import dev.zipshare.ui.admin.groupTitle
import dev.zipshare.ui.admin.settingLabel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Server settings are grouped by the key's leading lowercase word, so repeating that word in every
 * row inside the group ("Features / featuresDeleteOnMaxViews") is pure noise.
 */
class SettingLabelTest {

    @Test
    fun `strips the group prefix and reads as words`() {
        assertEquals("Delete on max views", settingLabel("featuresDeleteOnMaxViews", "features"))
        assertEquals("Temp directory", settingLabel("coreTempDirectory", "core"))
        assertEquals("Default format", settingLabel("filesDefaultFormat", "files"))
    }

    @Test
    fun `single-word remainders still work`() {
        assertEquals("Route", settingLabel("filesRoute", "files"))
        assertEquals("Max", settingLabel("chunksMax", "chunks"))
        assertEquals("Enabled", settingLabel("invitesEnabled", "invites"))
    }

    @Test
    fun `a key that is only the group name falls back to the key`() {
        // Nothing left after stripping - an empty label would be worse than a redundant one.
        assertEquals("core", settingLabel("core", "core"))
    }

    @Test
    fun `digits do not split words apart`() {
        assertEquals("Totp issuer", settingLabel("mfaTotpIssuer", "mfa"))
        assertEquals("V3 import", settingLabel("serverV3Import", "server"))
    }

    @Test
    fun `acronym groups are not naively capitalised`() {
        assertEquals("HTTP", groupTitle("http"))
        assertEquals("OAuth", groupTitle("oauth"))
        assertEquals("MFA", groupTitle("mfa"))
        assertEquals("URLs", groupTitle("urls"))
        assertEquals("Features", groupTitle("features"))
    }
}
