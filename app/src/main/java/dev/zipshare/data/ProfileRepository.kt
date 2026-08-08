package dev.zipshare.data

import dev.zipshare.data.model.Profile
import dev.zipshare.data.net.ZiplineClients
import dev.zipshare.data.prefs.SecureStore
import dev.zipshare.log.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import androidx.core.content.edit

/**
 * Profiles (including tokens) live only in EncryptedSharedPreferences. Nothing here is logged.
 *
 * Nothing touches disk in the constructor. Opening EncryptedSharedPreferences pulls in the
 * Keystore and Tink, which costs hundreds of milliseconds on first use - and Hilt builds this
 * singleton on the main thread during first composition, so doing it here used to block the
 * first frame (StrictMode DiskReadViolation, ~1s on an emulator). The initial read now happens
 * on an IO coroutine and callers that need certainty use [awaitReady].
 */
@Singleton
class ProfileRepository @Inject constructor(
    private val secure: SecureStore,
    private val clients: ZiplineClients,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

    init {
        scope.launch {
            val loaded = load()
            val storedActive = runCatching { secure.prefs.getString(KEY_ACTIVE, null) }.getOrNull()
            _profiles.value = loaded
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
        val next = _profiles.value.toMutableList()
        val i = next.indexOfFirst { it.id == profile.id }
        if (i >= 0) next[i] = profile else next += profile
        _profiles.value = next
        // Label only - the profile carries the token, which must never reach the log.
        AppLog.log("profile", "${if (i >= 0) "updated" else "added"} '${profile.label}'")
        clients.invalidate(profile.id)
        if (_activeId.value == null) setActive(profile.id)
        persist(next)
    }

    fun delete(id: String) {
        AppLog.log("profile", "deleted '${_profiles.value.firstOrNull { it.id == id }?.label}'")
        val next = _profiles.value.filterNot { it.id == id }
        _profiles.value = next
        clients.invalidate(id)
        if (_activeId.value == id) setActive(next.firstOrNull()?.id)
        persist(next)
    }

    fun setActive(id: String?) {
        AppLog.log("profile", "active -> '${_profiles.value.firstOrNull { it.id == id }?.label}'")
        _activeId.value = id
        // Encrypting the write is cheap once warm, but keep it off the caller's thread regardless.
        scope.launch {
            secure.prefs.edit { if (id == null) remove(KEY_ACTIVE) else putString(KEY_ACTIVE, id) }
        }
    }

    /** E2001: the token is dead - flag the profile so the UI can prompt for a new one. */
    fun markUnauthenticated(id: String) {
        byId(id)?.let { upsert(it.copy(authenticated = false)) }
    }

    private fun load(): List<Profile> = runCatching {
        secure.prefs.getString(KEY_PROFILES, null)
            ?.let { json.decodeFromString(ListSerializer(Profile.serializer()), it) }
    }.getOrNull() ?: emptyList()

    private fun persist(list: List<Profile>) {
        scope.launch {
            secure.prefs.edit {
                putString(KEY_PROFILES, json.encodeToString(ListSerializer(Profile.serializer()), list))
            }
        }
    }

    private companion object {
        const val KEY_PROFILES = "profiles_v1"
        const val KEY_ACTIVE = "active_profile"
    }
}
