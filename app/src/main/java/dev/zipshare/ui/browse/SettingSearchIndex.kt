package dev.zipshare.ui.browse

import dev.zipshare.ui.search.SearchEntry
import dev.zipshare.ui.search.serverSettingSearchEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Keeps the live server-settings search rows in step with the active profile.
 *
 * It owns three rules that were previously tangled in the view model and easy to get subtly wrong:
 *  - fetch a server's keys at most once (until it is switched away from), so search does not
 *    re-hit `/api/server/settings` on every recomposition;
 *  - drop the previous server's rows the moment the active profile changes, so a switch never
 *    leaves the old server's settings showing;
 *  - publish a response only while its profile is still active, so a slower earlier fetch cannot
 *    overwrite a newer server's keys.
 *
 * Pure Kotlin (no Android, no Retrofit) so those ordering rules are unit-testable with a
 * controllable scope and a hand-completed fetch. [fetchKeys] returns the raw setting keys for a
 * profile id, or null on any failure - which is simply forgotten so the next [sync] retries.
 */
class SettingSearchIndex(
    private val scope: CoroutineScope,
    private val activeProfileId: () -> String?,
    private val fetchKeys: suspend (profileId: String) -> List<String>?,
) {
    private val _entries = MutableStateFlow<List<SearchEntry>>(emptyList())
    val entries: StateFlow<List<SearchEntry>> = _entries

    /** The profile whose keys are loaded or in flight; null once a load failed, to allow a retry. */
    private var loadedProfileId: String? = null

    /**
     * Bring the index in line with [profileId]. A no-op while that profile is already loaded or
     * loading. Only admins are answered by the endpoint, so a non-admin (or a signed-out null
     * profile) just clears any prior rows. Safe to call repeatedly.
     */
    fun sync(profileId: String?, isAdmin: Boolean) {
        if (profileId == loadedProfileId) return
        loadedProfileId = profileId
        // New server (or sign-out): the old server's rows must not linger while the new load runs.
        _entries.value = emptyList()
        if (profileId == null || !isAdmin) return
        scope.launch {
            val keys = fetchKeys(profileId) ?: run {
                // Failed: forget it so a later sync retries - but only if nothing newer took over.
                if (loadedProfileId == profileId) loadedProfileId = null
                return@launch
            }
            // A switch may have happened while the request was in flight; the newest server wins.
            if (activeProfileId() == profileId) {
                _entries.value = keys.map(::serverSettingSearchEntry)
            }
        }
    }
}
