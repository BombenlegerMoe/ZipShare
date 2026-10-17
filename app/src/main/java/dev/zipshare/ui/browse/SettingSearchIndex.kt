package dev.zipshare.ui.browse

import dev.zipshare.ui.search.SearchEntry
import dev.zipshare.ui.search.serverSettingSearchEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Keeps the live server-settings search rows in step with the active profile and role.
 *
 * It owns the rules that are easy to get subtly wrong:
 *  - fetch a server's keys at most once while it stays active and readable, so search does not
 *    re-hit `/api/server/settings` on every recomposition;
 *  - drop the rows the moment the profile changes or the role no longer allows reading them;
 *  - publish a response only if it is still the one wanted, so a slower earlier fetch cannot
 *    overwrite a newer server's keys;
 *  - the role arrives after the profile (it needs `/api/user`), so a call made before it is known
 *    must not count as "loaded" - that exact ordering left 1.4.7 never fetching at all.
 *
 * Pure Kotlin (no Android, no Retrofit) so those rules are unit-testable with a controllable scope
 * and a hand-completed fetch. [fetchKeys] returns the raw setting keys for a profile id, or null on
 * any failure - which is simply forgotten so the next [sync] retries.
 */
class SettingSearchIndex(
    private val scope: CoroutineScope,
    private val fetchKeys: suspend (profileId: String) -> List<String>?,
) {
    private val _entries = MutableStateFlow<List<SearchEntry>>(emptyList())
    val entries: StateFlow<List<SearchEntry>> = _entries

    /** The profile whose keys are loaded or in flight; null when none may or could be loaded. */
    private var wanted: String? = null

    /**
     * Bring the index in line with [profileId] and [canRead] (only the super administrator is
     * answered by the endpoint since Zipline 4.7). A no-op while that profile is already loaded or
     * loading. Safe to call repeatedly.
     */
    fun sync(profileId: String?, canRead: Boolean) {
        val target = profileId?.takeIf { canRead }
        if (target == wanted) return
        wanted = target
        // New server, sign-out or lost role: the old rows must not linger while anything else runs.
        _entries.value = emptyList()
        if (target == null) return
        scope.launch {
            val keys = fetchKeys(target)
            // A switch or role change may have happened while the request was in flight.
            if (wanted != target) return@launch
            if (keys == null) {
                wanted = null // forget the failure so a later sync retries
                return@launch
            }
            _entries.value = keys.map(::serverSettingSearchEntry)
        }
    }
}
