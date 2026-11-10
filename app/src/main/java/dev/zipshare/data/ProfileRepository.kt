package dev.zipshare.data

import dev.zipshare.data.model.Profile
import dev.zipshare.data.net.ZiplineClients
import dev.zipshare.data.prefs.SecureStore
import dev.zipshare.log.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Profiles (including tokens) live only in [SecureStore]. Nothing here is logged.
 *
 * Nothing touches disk in the constructor. Opening the secure store pulls in the Keystore (and,
 * once, the migration off the old encrypted prefs), which costs hundreds of milliseconds on first
 * use - and Hilt builds this singleton on the main thread during first composition, so doing it
 * here used to block the first frame (StrictMode DiskReadViolation, ~1s on an emulator). The
 * initial read now happens on an IO coroutine and callers that need certainty use [awaitReady].
 */
@Singleton
class ProfileRepository @Inject constructor(
    private val secure: SecureStore,
    private val clients: ZiplineClients,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // One write at a time, in the order they were made. On the plain IO pool two quick changes
    // (an import adds servers one by one) could land out of order, and the older list would win.
    private val writes = Dispatchers.IO.limitedParallelism(1)

    private val _profiles = MutableStateFlow<List<Profile>>(emptyList())
    val profiles: StateFlow<List<Profile>> = _profiles

    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId

    private val _ready = MutableStateFlow(false)

    /** True once the first read off disk has completed. */
    val ready: StateFlow<Boolean> = _ready

    val active: Flow<Profile?> = combine(_profiles, _activeId) { list, id ->
        list.firstOrNull { it.id == id } ?: list.firstOrNull()
    }

    /**
     * The stored list could not be read. Writing now would replace every saved server with
     * whatever this session added, so writes are held back until the next start reads it fine.
     */
    @Volatile
    private var loadFailed = false

    init {
        scope.launch {
            // A few tries: a Keystore hiccup right after boot is usually gone a moment later.
            var loaded: List<Profile>? = null
            for (attempt in 1..3) {
                loaded = load() ?: run { delay(500L * attempt); null }
                if (loaded != null) break
            }
            if (loaded == null) {
                loadFailed = true
                AppLog.log("profile", "could not read saved servers; not overwriting them")
            }
            val storedActive = runCatching { secure.getString(KEY_ACTIVE) }.getOrNull()
            _profiles.value = loaded.orEmpty()
            _activeId.value = storedActive
            _ready.value = true
        }
    }

    /**
     * Suspends until profiles have been read from disk. Call this from any background entry point
     * (worker, share target) before using the synchronous accessors, or it may see an empty list.
     */
    suspend fun awaitReady() {
        if (_ready.value) return
        _ready.first { it }
    }

    fun activeNow(): Profile? =
        _profiles.value.firstOrNull { it.id == _activeId.value } ?: _profiles.value.firstOrNull()

    fun byId(id: String): Profile? = _profiles.value.firstOrNull { it.id == id }

    fun upsert(profile: Profile) {
        var existed = false
        // Atomic: the worker and the UI can both change the list at the same moment.
        val next = _profiles.updateAndGet { list ->
            existed = list.any { it.id == profile.id }
            if (existed) list.map { if (it.id == profile.id) profile else it } else list + profile
        }
        // Label only - the profile carries the token, which must never reach the log.
        AppLog.log("profile", "${if (existed) "updated" else "added"} '${profile.label}'")
        clients.invalidate(profile.id)
        if (_activeId.value == null) setActive(profile.id)
        persist(next)
    }

    fun delete(id: String) {
        AppLog.log("profile", "deleted '${_profiles.value.firstOrNull { it.id == id }?.label}'")
        val next = _profiles.updateAndGet { list -> list.filterNot { it.id == id } }
        clients.invalidate(id)
        if (_activeId.value == id) setActive(next.firstOrNull()?.id)
        persist(next)
    }

    fun setActive(id: String?) {
        AppLog.log("profile", "active -> '${_profiles.value.firstOrNull { it.id == id }?.label}'")
        _activeId.value = id
        // Encrypting the write is cheap once warm, but keep it off the caller's thread regardless.
        scope.launch(writes) {
            secure.putString(KEY_ACTIVE, id)
        }
    }

    /** E2001: the token is dead - flag the profile so the UI can prompt for a new one. */
    fun markUnauthenticated(id: String) {
        byId(id)?.let { upsert(it.copy(authenticated = false)) }
    }

    /** Null when the stored list exists but could not be read; empty when there is none. */
    private fun load(): List<Profile>? = runCatching {
        secure.getString(KEY_PROFILES)
            ?.let { json.decodeFromString(ListSerializer(Profile.serializer()), it) }
            .orEmpty()
    }.getOrNull()

    private fun persist(list: List<Profile>) {
        if (loadFailed) return
        scope.launch(writes) {
            secure.putString(KEY_PROFILES, json.encodeToString(ListSerializer(Profile.serializer()), list))
        }
    }

    private companion object {
        const val KEY_PROFILES = "profiles_v1"
        const val KEY_ACTIVE = "active_profile"
    }
}
