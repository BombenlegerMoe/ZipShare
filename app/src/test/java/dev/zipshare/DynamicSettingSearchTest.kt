package dev.zipshare

import dev.zipshare.ui.Routes
import dev.zipshare.ui.search.appSearchIndex
import dev.zipshare.ui.search.searchEntries
import dev.zipshare.ui.search.serverSettingSearchEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The static index only lists the common instance-settings keys; the rest come from whatever the
 * admin's own server returns, turned into rows by [serverSettingSearchEntry] and merged in at
 * runtime. These pin that a key the static list never knew about - an OAuth provider, a Discord
 * webhook, a PWA field - is built correctly and findable once merged.
 */
class DynamicSettingSearchTest {

    // Keys a real Zipline exposes that the hand-listed subset omits.
    private val liveKeys = listOf(
        "oauthDiscordClientId", "oauthGoogleClientId", "oauthGithubClientSecret", "oauthBypassers",
        "discordWebhookUrl", "discordUsername", "discordOnUploadEnabled",
        "pwaEnabled", "pwaThemeColor",
        "mfaTotpEnabled", "mfaTotpIssuer", "mfaPasskeys",
        "httpWebhookOnUpload",
    )
    private val merged = appSearchIndex + liveKeys.map(::serverSettingSearchEntry)

    private fun titles(query: String, admin: Boolean = true) =
        searchEntries(query, admin, merged).map { it.title }

    @Test
    fun `the anchor is the raw key so the screen can jump to that row`() {
        val e = serverSettingSearchEntry("oauthDiscordClientId")
        assertEquals("oauthDiscordClientId", e.anchor)
        assertEquals(Routes.ADMIN_SETTINGS, e.route)
        assertTrue(e.adminOnly)
        // Group is the leading lowercase run, mapped through the same acronym table the screen uses.
        assertEquals("Server settings > OAuth", e.where)
    }

    @Test
    fun `a multi-segment key reads as words`() {
        assertEquals("Discord client id", serverSettingSearchEntry("oauthDiscordClientId").title)
        assertEquals("Webhook url", serverSettingSearchEntry("discordWebhookUrl").title)
        // group "http", remainder "WebhookOnUpload"
        assertEquals("Webhook on upload", serverSettingSearchEntry("httpWebhookOnUpload").title)
    }

    @Test
    fun `a key with no lowercase prefix falls back to the other group`() {
        assertEquals("Server settings > Other", serverSettingSearchEntry("XyzKey").where)
    }

    @Test
    fun `oauth and other provider settings are findable once the live list is merged`() {
        assertTrue("discord", titles("discord").any { it.contains("Discord", ignoreCase = true) })
        assertTrue("google", titles("google").isNotEmpty())
        assertTrue("pwa", titles("pwa").isNotEmpty())
        assertTrue("totp issuer", titles("issuer").contains("Totp issuer"))
        assertTrue("raw key", titles("oauthbypassers").isNotEmpty())
    }

    @Test
    fun `a live key overlapping the static seed is not listed twice`() {
        // featuresOauthRegistration is already in the static seed; feed it as a live key too.
        val withOverlap = appSearchIndex + serverSettingSearchEntry("featuresOauthRegistration")
        val hits = searchEntries("oauth registration", isAdmin = true, withOverlap)
            .filter { it.anchor == "featuresOauthRegistration" }
        assertEquals(1, hits.size)
    }

    @Test
    fun `live instance settings stay hidden from non-admins`() {
        // A raw key that lives only in the admin/dynamic set. ("discord" would wrongly pass here
        // via the non-admin Sharing entry, whose keywords mention Discord link embeds.)
        assertTrue(titles("oauthbypassers", admin = false).isEmpty())
        assertTrue(titles("pwathemecolor", admin = false).isEmpty())
        // ...but an admin does find them.
        assertTrue(titles("oauthbypassers", admin = true).isNotEmpty())
    }
}
