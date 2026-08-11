package dev.zipshare

import dev.zipshare.ui.browse.SettingSearchIndex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ordering rules that were previously tangled in the view model: fetch a server's keys once,
 * drop the old server's rows the instant it is switched away from, and never let a slower earlier
 * response overwrite the server now active. [Dispatchers.Unconfined] runs each launch up to its
 * first suspension inline, and a hand-completed [CompletableDeferred] per profile lets the test
 * decide the order responses arrive - so the races are deterministic, no test dispatcher needed.
 */
class SettingSearchIndexTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)

    /** One controllable fetch per profile id, so completion order is the test's to decide. */
    private class Fetches {
        val calls = mutableListOf<String>()
        private val pending = HashMap<String, CompletableDeferred<List<String>?>>()
        val fetch: suspend (String) -> List<String>? = { id ->
            calls += id
            pending.getOrPut(id) { CompletableDeferred() }.await()
        }

        fun complete(id: String, keys: List<String>?) {
            pending.getOrPut(id) { CompletableDeferred() }.complete(keys)
            pending.remove(id) // a later fetch for the same id gets a fresh, uncompleted deferred
        }
    }

    @Test
    fun `a non-admin never hits the endpoint`() {
        val f = Fetches()
        val index = SettingSearchIndex(scope, activeProfileId = { "a" }, fetchKeys = f.fetch)
        index.sync("a", isAdmin = false)
        assertTrue("non-admins must not call the admin-only endpoint", f.calls.isEmpty())
        assertTrue(index.entries.value.isEmpty())
    }

    @Test
    fun `an admin loads a server's keys once`() {
        val f = Fetches()
        val index = SettingSearchIndex(scope, { "a" }, f.fetch)
        index.sync("a", isAdmin = true)
        f.complete("a", listOf("pwaEnabled")) // group "pwa" -> title "Enabled"
        assertEquals(listOf("Enabled"), index.entries.value.map { it.title })
        // A second sync for the same server does not fetch again.
        index.sync("a", isAdmin = true)
        assertEquals(listOf("a"), f.calls)
    }

    @Test
    fun `switching servers drops the old rows immediately, then loads the new`() {
        val f = Fetches()
        var active = "a"
        val index = SettingSearchIndex(scope, { active }, f.fetch)
        index.sync("a", isAdmin = true); f.complete("a", listOf("pwaEnabled"))
        assertTrue(index.entries.value.isNotEmpty())

        active = "b"
        index.sync("b", isAdmin = true)
        assertTrue("the old server's rows must be gone before the new load arrives", index.entries.value.isEmpty())
        f.complete("b", listOf("oauthDiscordClientId"))
        assertEquals(listOf("Discord client id"), index.entries.value.map { it.title })
    }

    @Test
    fun `a slower earlier response cannot overwrite the server now active`() {
        val f = Fetches()
        var active = "a"
        val index = SettingSearchIndex(scope, { active }, f.fetch)
        index.sync("a", isAdmin = true) // fetch A in flight
        active = "b"
        index.sync("b", isAdmin = true) // fetch B in flight
        f.complete("b", listOf("oauthDiscordClientId")) // B answers first
        f.complete("a", listOf("pwaEnabled"))           // A answers late - must be ignored
        assertEquals("the active server B must win", listOf("Discord client id"), index.entries.value.map { it.title })
    }

    @Test
    fun `a failed load is forgotten so the next sync retries and can then succeed`() {
        val f = Fetches()
        val index = SettingSearchIndex(scope, { "a" }, f.fetch)
        index.sync("a", isAdmin = true)
        f.complete("a", null) // first attempt fails
        assertTrue(index.entries.value.isEmpty())

        index.sync("a", isAdmin = true) // e.g. user opens search: it must try once more
        f.complete("a", listOf("pwaEnabled"))
        assertEquals(listOf("a", "a"), f.calls)
        assertTrue(index.entries.value.isNotEmpty())
    }
}
