package dev.zipshare.ui.home

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.zipshare.data.ProfileRepository
import dev.zipshare.data.db.HistoryDao
import dev.zipshare.data.db.HistoryEntry
import dev.zipshare.data.model.Profile
import dev.zipshare.data.model.UploadOptions
import dev.zipshare.data.net.ErrorAction
import dev.zipshare.data.net.UserStats
import dev.zipshare.data.net.ZFile
import dev.zipshare.data.net.ZFolder
import dev.zipshare.data.net.ZUser
import dev.zipshare.data.net.ZiplineClients
import dev.zipshare.data.net.ZiplineException
import dev.zipshare.data.net.unwrap
import dev.zipshare.data.prefs.AppSettings
import dev.zipshare.data.prefs.SettingsStore
import dev.zipshare.log.AppLog
import dev.zipshare.ui.userMessage
import dev.zipshare.upload.UploadEnqueuer
import dev.zipshare.upload.UploadInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class HomeState(
    val profiles: List<Profile> = emptyList(),
    val active: Profile? = null,
    val folders: List<ZFolder> = emptyList(),
    val options: UploadOptions = UploadOptions.DEFAULT,
    val sheetOpen: Boolean = false,
    val pending: List<Uri> = emptyList(),
    /** Resolved names of [pending], in the same order, shown in the sheet before uploading. */
    val pendingNames: List<String> = emptyList(),
    /** True when any pending file came from the photo picker, which hides real file names. */
    val namesRedacted: Boolean = false,
    val error: String? = null,
    /** False until profiles have been read off disk; distinguishes "loading" from "no server". */
    val profilesReady: Boolean = false,
    // --- dashboard ---
    val user: ZUser? = null,
    val stats: UserStats? = null,
    val recents: List<ZFile> = emptyList(),
    val syncing: Boolean = false,
    /** Set when the dashboard sync itself failed, so the UI can show it inline rather than as a toast. */
    val syncError: String? = null,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profiles: ProfileRepository,
    private val clients: ZiplineClients,
    private val enqueuer: UploadEnqueuer,
    private val settings: SettingsStore,
    private val history: HistoryDao,
) : ViewModel() {

    val entries: StateFlow<List<HistoryEntry>> = history.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val appSettings: StateFlow<AppSettings> = settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state

    init {
        viewModelScope.launch {
            profiles.profiles.collect { list -> _state.value = _state.value.copy(profiles = list) }
        }
        viewModelScope.launch {
            profiles.ready.collect { _state.value = _state.value.copy(profilesReady = it) }
        }
        // Must observe the active *selection*, not just the profile list: switching servers from the
        // Servers screen changes only activeId, which would otherwise never reach the dashboard.
        viewModelScope.launch {
            profiles.active
                .distinctUntilChanged { old, new -> old?.id == new?.id }
                .collect { active ->
                    _state.value = _state.value.copy(
                        active = active,
                        stats = null,
                        recents = emptyList(),
                        user = null,
                        syncError = null,
                    )
                    refresh()
                }
        }
        viewModelScope.launch {
            // Resolve the suspending read FIRST. Writing
            // `_state.value = _state.value.copy(x = suspendCall())` reads the receiver before
            // suspending, so it would write a stale snapshot back and clobber whatever the
            // profile collector set while DataStore was warming up.
            val defaults = settings.current().defaultOptions
            _state.value = _state.value.copy(options = defaults)
        }
        // Re-sync when the user changes how many recents they want.
        viewModelScope.launch {
            settings.settings
                .map { it.recentCount to it.showRecents }
                .distinctUntilChanged()
                .collect { refresh() }
        }
        // Every completed upload writes a history row - picker, share target, worker retry and
        // text upload all land here - so that insert is the signal that the server has new files.
        // drop(1) skips the initial emission, which is just the existing table.
        viewModelScope.launch {
            history.observeAll()
                .map { rows -> rows.size to rows.firstOrNull()?.remoteId }
                .distinctUntilChanged()
                .drop(1)
                .collect { refresh() }
        }
    }

    /** Pulls the dashboard: profile, stats and the configured number of recent uploads. */
    fun refresh() {
        val active = profiles.activeNow() ?: run {
            _state.value = _state.value.copy(stats = null, recents = emptyList(), user = null)
            return
        }
        viewModelScope.launch {
            val cfg = settings.current()
            _state.value = _state.value.copy(syncing = true, syncError = null)
            val api = clients.api(active)

            val result = runCatching {
                val user = api.user().unwrap().user
                val stats = if (cfg.showStats || cfg.showTypes) api.stats().unwrap() else null
                val recents =
                    if (cfg.showRecents) api.recent(cfg.recentCount).unwrap() else emptyList()
                Triple(user, stats, recents)
            }

            result.onSuccess { (user, stats, recents) ->
                _state.value = _state.value.copy(
                    user = user,
                    stats = stats,
                    recents = recents,
                    syncing = false,
                    syncError = null,
                )
            }.onFailure { e ->
                AppLog.log("sync", "dashboard sync failed: ${e.message}")
                sideEffect(active, e)
                _state.value = _state.value.copy(
                    syncing = false,
                    syncError = e.userMessage("Sync failed"),
                )
            }
        }
    }

    /** The active-profile collector above picks the change up and re-syncs. */
    fun selectProfile(id: String) {
        profiles.setActive(id)
        _state.value = _state.value.copy(folders = emptyList())
    }

    fun filesPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val active = profiles.activeNow()
        if (active == null) {
            _state.value = _state.value.copy(error = "Add a server profile first.")
            return
        }
        viewModelScope.launch {
            val cfg = settings.current()
            // Always start from the saved defaults, so one upload's tweaks never leak into the next.
            val options = cfg.defaultOptions
            if (cfg.skipUploadSheet) {
                _state.value = _state.value.copy(options = options, pending = emptyList())
                runCatching { enqueuer.enqueue(uris, active.id, options) }
                    .onFailure { e ->
                        _state.value = _state.value.copy(
                            error = e.message ?: "Could not start the upload.",
                        )
                    }
            } else {
                val names = withContext(Dispatchers.IO) {
                    uris.map { runCatching { UploadInput.meta(context, it).name }.getOrDefault("?") }
                }
                _state.value = _state.value.copy(
                    pending = uris,
                    pendingNames = names,
                    namesRedacted = uris.any(UploadInput::isPickerRedacted),
                    sheetOpen = true,
                    options = options,
                )
                loadFolders()
            }
        }
    }

    /** Discards per-upload tweaks and restores the saved defaults. */
    fun resetOptionsToDefaults() {
        viewModelScope.launch {
            _state.value = _state.value.copy(options = settings.current().defaultOptions)
        }
    }

    fun updateOptions(options: UploadOptions) {
        _state.value = _state.value.copy(options = options)
    }

    fun dismissSheet() {
        _state.value = _state.value.copy(
            sheetOpen = false,
            pending = emptyList(),
            pendingNames = emptyList(),
            namesRedacted = false,
        )
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    fun confirmUpload() {
        val s = _state.value
        val active = s.active ?: return
        viewModelScope.launch {
            runCatching { enqueuer.enqueue(s.pending, active.id, s.options) }
                .onFailure {
                    _state.value = _state.value.copy(
                        error = it.message ?: "Could not start the upload.",
                    )
                }
            dismissSheet()
        }
    }

    fun loadFolders() {
        val active = profiles.activeNow() ?: return
        viewModelScope.launch {
            runCatching { clients.api(active).folders(noIncludeFiles = true).unwrap() }
                .onSuccess { _state.value = _state.value.copy(folders = it) }
                .onFailure { e -> report(active, e) }
        }
    }

    /**
     * Forgets the on-device upload records only. Deliberately does NOT touch the server - unlike
     * [delete], which removes the actual file. The confirmation dialog spells that out, because
     * the two live next to each other in the same list.
     */
    fun clearHistory() {
        viewModelScope.launch { history.clear() }
    }

    fun delete(entry: HistoryEntry) {
        val profile = profiles.byId(entry.profileId)
        if (profile == null) {
            viewModelScope.launch { history.delete(entry.remoteId) }
            return
        }
        viewModelScope.launch {
            runCatching { clients.api(profile).deleteFile(entry.remoteId).unwrap() }
                .onSuccess {
                    history.delete(entry.remoteId)
                    refresh()
                }
                .onFailure { e ->
                    // Already gone server-side, so drop the local row too.
                    if (e is ZiplineException && (e.code == 9002 || e.code == 4000)) {
                        history.delete(entry.remoteId)
                    }
                    report(profile, e)
                }
        }
    }

    private fun report(profile: Profile, e: Throwable) {
        sideEffect(profile, e)
        _state.value = _state.value.copy(error = e.userMessage("Network error"))
    }

    private fun sideEffect(profile: Profile, e: Throwable) {
        if (e !is ZiplineException) return
        when (e.action) {
            ErrorAction.REAUTH -> profiles.markUnauthenticated(profile.id)
            ErrorAction.CLEAR_FOLDER -> viewModelScope.launch { settings.clearDefaultUploadFolder() }
            else -> Unit
        }
    }
}
