package dev.zipshare.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.zipshare.data.ProfileRepository
import dev.zipshare.data.prefs.SettingsStore
import dev.zipshare.data.model.LinkFormat
import dev.zipshare.data.model.Profile
import dev.zipshare.data.net.BulkDeleteBody
import dev.zipshare.data.net.BulkPatchBody
import dev.zipshare.data.net.CreateFolderBody
import dev.zipshare.data.net.CreateTagBody
import dev.zipshare.data.net.CreateUrlBody
import dev.zipshare.data.net.CreateUserBody
import dev.zipshare.data.net.DeleteFolderBody
import dev.zipshare.data.net.DeleteUserBody
import dev.zipshare.data.net.PatchFolderBody
import dev.zipshare.data.net.PatchUserBody
import dev.zipshare.data.net.QuotaBody
import dev.zipshare.data.net.FileSort
import dev.zipshare.data.net.PatchFileBody
import dev.zipshare.data.net.PatchTagBody
import dev.zipshare.data.net.ZFile
import dev.zipshare.data.net.ZTag
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import dev.zipshare.data.net.ZFolder
import dev.zipshare.data.net.ZLimitedUser
import dev.zipshare.data.net.ZUrl
import dev.zipshare.data.net.ZUser
import dev.zipshare.data.net.ZiplineApi
import dev.zipshare.data.net.ZiplineClients
import dev.zipshare.ui.search.SearchEntry
import dev.zipshare.ui.search.serverSettingSearchEntry
import dev.zipshare.data.net.unwrap
import dev.zipshare.ui.callActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BrowseState(
    val profiles: List<Profile> = emptyList(),
    /** False until profiles have been read off disk, so first run does not flash the sign-in screen. */
    val profilesReady: Boolean = false,
    val active: Profile? = null,
    val me: ZUser? = null,
    val loading: Boolean = false,
    val error: String? = null,
    // files
    val files: List<ZFile> = emptyList(),
    val page: Int = 1,
    val totalPages: Int = 1,
    val folderFilter: String? = null,
    val folderFilterName: String? = null,
    /** False until a file page has actually arrived, so the first load is never skipped. */
    val filesLoaded: Boolean = false,
    // --- sorting / searching ---
    val sort: FileSort = FileSort.CREATED,
    val ascending: Boolean = false,
    val search: String = "",
    val searchField: String = "name",
    val favouritesOnly: Boolean = false,
    // --- selection ---
    val selected: Set<String> = emptySet(),
    val selecting: Boolean = false,
    // --- tags ---
    val tags: List<ZTag> = emptyList(),
    // other lists
    val folders: List<ZFolder> = emptyList(),
    val urls: List<ZUrl> = emptyList(),
    val users: List<ZLimitedUser> = emptyList(),
    /** True once /api/users answered successfully, so an empty list isn't misreported as a denial. */
    val usersLoaded: Boolean = false,
)

/**
 * Backs the Files / Folders / URLs / Users pages. Each page gets its own instance (nav-scoped)
 * and calls only the loader it needs.
 */
@HiltViewModel
class BrowseViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val clients: ZiplineClients,
    settings: SettingsStore,
) : ViewModel() {

    /** Which shape the copy buttons put on the clipboard; see [dev.zipshare.data.model.formatLink]. */
    val linkFormat: StateFlow<LinkFormat> = settings.settings
        .map { it.linkFormat }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LinkFormat.PLAIN)

    private val _state = MutableStateFlow(BrowseState())
    val state: StateFlow<BrowseState> = _state

    val profileList: StateFlow<List<Profile>> = profiles.profiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            profiles.profiles.collect { _state.value = _state.value.copy(profiles = it) }
        }
        viewModelScope.launch {
            profiles.ready.collect { _state.value = _state.value.copy(profilesReady = it) }
        }
        viewModelScope.launch {
            profiles.active
                .distinctUntilChanged { a, b -> a?.id == b?.id }
                .collect { _state.value = _state.value.copy(active = it) }
        }
    }

    fun selectProfile(id: String) = profiles.setActive(id)

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    /** Needed for the admin gate in the drawer. */
    fun loadMe() = launchWithProfile { api, _ ->
        // Suspend first, then touch state - see the note in HomeViewModel about stale receivers.
        val me = api.user().unwrap().user
        _state.value = _state.value.copy(me = me)
    }

    /**
     * Every instance-settings key the active server actually exposes, as search rows, so search
     * covers whatever this Zipline version happens to have rather than only a hand-kept subset.
     *
     * Deliberately not [launchWithProfile]: `/api/server/settings` is admin-only and 403s for
     * everyone else, and a failed background prefetch must not raise the shared error banner. It
     * is gated on [isAdmin] and swallows failure - the static seed still covers the common keys.
     * Fetched once per profile; re-run when the active server changes.
     */
    fun loadSettingSearchIndex(isAdmin: Boolean) {
        val profile = profiles.activeNow() ?: return
        if (!isAdmin || profile.id == settingSearchProfileId) return
        settingSearchProfileId = profile.id
        viewModelScope.launch {
            val keys = runCatching {
                clients.api(profile).serverSettings().unwrap().settings.keys
            }.getOrNull() ?: run {
                // Let a later attempt retry rather than caching an empty result for this profile.
                settingSearchProfileId = null
                return@launch
            }
            _settingSearch.value = keys.map(::serverSettingSearchEntry)
        }
    }

    private var settingSearchProfileId: String? = null
    private val _settingSearch = MutableStateFlow<List<SearchEntry>>(emptyList())
    val settingSearch: StateFlow<List<SearchEntry>> = _settingSearch

    fun loadFiles(page: Int = _state.value.page, folder: String? = _state.value.folderFilter) =
        launchWithProfile { api, _ ->
            val s = _state.value
            val query = s.search.trim().takeIf { it.isNotEmpty() }
            val result = api.files(
                page = page,
                perPage = PER_PAGE,
                sortBy = s.sort.wire,
                order = if (s.ascending) "asc" else "desc",
                folder = folder,
                // Sending searchField without a query makes the server filter on an empty string.
                searchField = query?.let { s.searchField },
                searchQuery = query,
                favorite = if (s.favouritesOnly) true else null,
            ).unwrap()
            _state.value = _state.value.copy(
                files = result.page,
                page = page,
                totalPages = result.pages ?: 1,
                folderFilter = folder,
                filesLoaded = true,
            )
        }

    /**
     * Applies the folder the screen was opened with.
     *
     * The screen calls this from a LaunchedEffect, which re-runs every time the composable is
     * recreated - and opening a file in the viewer then pressing back does exactly that. Resetting
     * unconditionally therefore threw the user back to page 1 on almost every return trip, so the
     * page is only reset when the filter genuinely changed or nothing has loaded yet.
     */
    fun setFolderFilter(id: String?, name: String?) {
        val s = _state.value
        if (!shouldReload(s.filesLoaded, s.folderFilter, id)) {
            // Same folder, already loaded: keep whatever page the user is on.
            return
        }
        _state.value = s.copy(folderFilter = id, folderFilterName = name, page = 1)
        loadFiles(page = 1, folder = id)
    }

    fun nextPage() {
        val s = _state.value
        if (s.page < s.totalPages) loadFiles(page = s.page + 1)
    }

    fun prevPage() {
        val s = _state.value
        if (s.page > 1) loadFiles(page = s.page - 1)
    }

    fun loadFolders() = launchWithProfile { api, _ ->
        val folders = api.folders(noIncludeFiles = true).unwrap()
        _state.value = _state.value.copy(folders = folders)
    }

    fun loadUrls() = launchWithProfile { api, _ ->
        val urls = api.urls().unwrap()
        _state.value = _state.value.copy(urls = urls)
    }

    fun loadUsers() = launchWithProfile { api, _ ->
        val users = api.users().unwrap()
        _state.value = _state.value.copy(users = users, usersLoaded = true)
    }

    // ---------- sorting / searching ----------

    fun setSort(sort: FileSort) {
        _state.value = _state.value.copy(sort = sort, page = 1)
        loadFiles(page = 1)
    }

    fun toggleOrder() {
        _state.value = _state.value.copy(ascending = !_state.value.ascending, page = 1)
        loadFiles(page = 1)
    }

    /** Typing only updates the box; the screen debounces before calling [applySearch]. */
    fun setSearch(text: String) {
        _state.value = _state.value.copy(search = text)
    }

    fun setSearchField(field: String) {
        _state.value = _state.value.copy(searchField = field, page = 1)
        if (_state.value.search.isNotBlank()) loadFiles(page = 1)
    }

    fun applySearch() {
        _state.value = _state.value.copy(page = 1)
        loadFiles(page = 1)
    }

    fun toggleFavouritesOnly() {
        _state.value = _state.value.copy(favouritesOnly = !_state.value.favouritesOnly, page = 1)
        loadFiles(page = 1)
    }

    // ---------- selection ----------

    fun toggleSelected(id: String) {
        val s = _state.value
        val next = if (id in s.selected) s.selected - id else s.selected + id
        _state.value = s.copy(selected = next, selecting = next.isNotEmpty())
    }

    fun startSelecting(id: String) {
        _state.value = _state.value.copy(selected = setOf(id), selecting = true)
    }

    fun clearSelection() {
        _state.value = _state.value.copy(selected = emptySet(), selecting = false)
    }

    fun selectAllOnPage() {
        val s = _state.value
        _state.value = s.copy(selected = s.files.map { it.id }.toSet(), selecting = true)
    }

    // ---------- single-file edits ----------

    fun toggleFavourite(file: ZFile) = launchWithProfile { api, _ ->
        val updated = api.patchFile(file.id, PatchFileBody(favorite = !file.favorite)).unwrap()
        replaceFile(updated)
    }

    /** Only the fields the user actually changed are sent. */
    fun patchFile(
        id: String,
        name: String? = null,
        maxViews: Int? = null,
        tags: List<String>? = null,
        password: String? = null,
    ) = launchWithProfile { api, _ ->
        val updated = api.patchFile(
            id,
            PatchFileBody(name = name, maxViews = maxViews, tags = tags, password = password),
        ).unwrap()
        replaceFile(updated)
    }

    /** Moving a single file reuses the bulk endpoint - there is no folder field on PATCH file. */
    fun moveToFolder(ids: Collection<String>, folderId: String) = launchWithProfile { api, _ ->
        api.bulkPatchFiles(BulkPatchBody(files = ids.toList(), folder = folderId)).unwrap()
        clearSelection()
        loadFiles()
    }

    // ---------- bulk ----------

    fun bulkFavourite(favourite: Boolean) = launchWithProfile { api, _ ->
        val ids = _state.value.selected.toList()
        if (ids.isEmpty()) return@launchWithProfile
        api.bulkPatchFiles(BulkPatchBody(files = ids, favorite = favourite)).unwrap()
        clearSelection()
        loadFiles()
    }

    fun bulkDelete() = launchWithProfile { api, _ ->
        val ids = _state.value.selected.toList()
        if (ids.isEmpty()) return@launchWithProfile
        // Explicitly true: deleting a file should remove the stored bytes, not just the record.
        api.bulkDeleteFiles(BulkDeleteBody(files = ids, deleteDatasourceFiles = true)).unwrap()
        clearSelection()
        loadFiles()
    }

    // ---------- tags ----------

    fun loadTags() = launchWithProfile { api, _ ->
        val tags = api.tags().unwrap()
        _state.value = _state.value.copy(tags = tags)
    }

    fun createTag(name: String, color: String) = launchWithProfile { api, _ ->
        api.createTag(CreateTagBody(name, color)).unwrap()
        val tags = api.tags().unwrap()
        _state.value = _state.value.copy(tags = tags)
    }

    fun editTag(id: String, name: String, color: String) = launchWithProfile { api, _ ->
        api.patchTag(id, PatchTagBody(name = name, color = color)).unwrap()
        val tags = api.tags().unwrap()
        // The tag is embedded in each file, so refresh the page to pick up the new name/colour.
        _state.value = _state.value.copy(tags = tags)
        loadFiles()
    }

    fun deleteTag(id: String) = launchWithProfile { api, _ ->
        api.deleteTag(id).unwrap()
        val tags = api.tags().unwrap()
        _state.value = _state.value.copy(tags = tags)
        loadFiles()
    }

    /**
     * Sets or clears a file password.
     *
     * Written as a raw JSON body on purpose: the converter omits nulls, and JSON null is exactly
     * how the server is told to remove a password. Through the typed body a "clear" silently
     * became "leave unchanged".
     */
    fun setFilePassword(id: String, password: String?) = launchWithProfile { api, _ ->
        val body = buildJsonObject {
            if (password.isNullOrBlank()) put("password", JsonNull) else put("password", JsonPrimitive(password))
        }
        val updated = api.patchFileRaw(id, body).unwrap()
        replaceFile(updated)
    }

    /** Swaps one file in the current page without refetching the whole list. */
    private fun replaceFile(updated: ZFile) {
        _state.value = _state.value.copy(
            files = _state.value.files.map { if (it.id == updated.id) updated else it },
        )
    }

    fun createFolder(name: String, isPublic: Boolean, parentId: String?) =
        launchWithProfile { api, _ ->
            api.createFolder(CreateFolderBody(name, isPublic, parentId)).unwrap()
            val folders = api.folders(noIncludeFiles = true).unwrap()
            _state.value = _state.value.copy(folders = folders)
        }

    fun patchFolder(
        id: String,
        name: String? = null,
        isPublic: Boolean? = null,
        allowUploads: Boolean? = null,
    ) = launchWithProfile { api, _ ->
        api.patchFolder(id, PatchFolderBody(name, isPublic, allowUploads)).unwrap()
        val folders = api.folders(noIncludeFiles = true).unwrap()
        _state.value = _state.value.copy(folders = folders)
    }

    /** [keepFiles] lifts the contents to the top level instead of deleting them with the folder. */
    fun deleteFolder(id: String, keepFiles: Boolean) = launchWithProfile { api, _ ->
        api.deleteFolder(
            id,
            DeleteFolderBody(
                delete = "folder",
                childrenAction = if (keepFiles) "root" else "cascade-files",
            ),
        ).unwrap()
        val folders = api.folders(noIncludeFiles = true).unwrap()
        _state.value = _state.value.copy(folders = folders)
    }

    fun patchUser(
        id: String,
        username: String? = null,
        password: String? = null,
        role: String? = null,
        quota: QuotaBody? = null,
    ) = launchWithProfile { api, _ ->
        api.patchUser(id, PatchUserBody(username, password, role, quota)).unwrap()
        val users = api.users().unwrap()
        _state.value = _state.value.copy(users = users, usersLoaded = true)
    }

    /** [alsoDeleteContent] removes everything the account uploaded, not just the account. */
    fun deleteUser(id: String, alsoDeleteContent: Boolean) = launchWithProfile { api, _ ->
        api.deleteUser(id, DeleteUserBody(delete = alsoDeleteContent)).unwrap()
        val users = api.users().unwrap()
        _state.value = _state.value.copy(users = users, usersLoaded = true)
    }

    fun createUrl(destination: String, vanity: String?) = launchWithProfile { api, _ ->
        api.createUrl(CreateUrlBody(destination, vanity?.takeIf { it.isNotBlank() })).unwrap()
        val urls = api.urls().unwrap()
        _state.value = _state.value.copy(urls = urls)
    }

    fun createUser(username: String, password: String, role: String) = launchWithProfile { api, _ ->
        api.createUser(CreateUserBody(username, password, role)).unwrap()
        val users = api.users().unwrap()
        _state.value = _state.value.copy(users = users, usersLoaded = true)
    }

    fun deleteFile(file: ZFile) = launchWithProfile { api, _ ->
        api.deleteFile(file.id).unwrap()
        _state.value = _state.value.copy(files = _state.value.files.filterNot { it.id == file.id })
    }

    fun deleteUrl(url: ZUrl) = launchWithProfile { api, _ ->
        api.deleteUrl(url.id).unwrap()
        _state.value = _state.value.copy(urls = _state.value.urls.filterNot { it.id == url.id })
    }

    private fun launchWithProfile(block: suspend (api: ZiplineApi, p: Profile) -> Unit) =
        _state.callActive(viewModelScope, profiles, clients, { l, e -> copy(loading = l, error = e) }, block)

    companion object {
        private const val PER_PAGE = 24

        /**
         * Whether [setFolderFilter] should discard the current page and reload from page 1.
         * Pure so the "don't reset on every return to the screen" rule can be unit tested.
         */
        internal fun shouldReload(loaded: Boolean, current: String?, requested: String?): Boolean =
            !loaded || current != requested
    }
}
