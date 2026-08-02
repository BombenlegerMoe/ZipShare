package dev.zipshare.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.zipshare.data.ProfileRepository
import dev.zipshare.data.net.DeleteSessionBody
import dev.zipshare.data.net.PatchMeBody
import dev.zipshare.data.net.SessionsResponse
import dev.zipshare.data.net.ZUser
import dev.zipshare.data.net.ZView
import dev.zipshare.data.net.ZiplineApi
import dev.zipshare.data.net.ZiplineClients
import dev.zipshare.data.net.ZiplineException
import dev.zipshare.data.net.unwrap
import dev.zipshare.log.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject

data class AccountState(
    val user: ZUser? = null,
    val token: String = "",
    val serverLabel: String? = null,
    val message: String? = null,
    val busy: Boolean = false,
    val sessions: SessionsResponse? = null,
    val sessionsError: String? = null,
)

/**
 * Backs the account menu in the top bar: who is signed in, their avatar, and the same actions
 * Zipline's web header offers - copy token, refresh token, settings, sign out.
 */
@HiltViewModel
class AccountViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val clients: ZiplineClients,
    private val notifications: dev.zipshare.upload.UploadNotifications,
) : ViewModel() {

    /** Reuses the upload path's clipboard helper, which marks the entry sensitive. */
    fun copyToken() {
        notifications.copyToClipboard(_state.value.token)
        _state.value = _state.value.copy(message = "Token copied.")
    }

    private val _state = MutableStateFlow(AccountState())
    val state: StateFlow<AccountState> = _state

    fun load() = viewModelScope.launch {
        profiles.awaitReady()
        val active = profiles.activeNow() ?: return@launch
        _state.value = _state.value.copy(token = active.token, serverLabel = active.label)

        val api = clients.api(active)
        val user = runCatching { api.user().unwrap().user }.getOrNull() ?: return@launch
        _state.value = _state.value.copy(user = user)

        // Some deployments omit the avatar from /api/user; it has its own endpoint, which
        // answers with the data URL as plain text. Absent avatar is normal, so failures are quiet.
        if (user.avatar.isNullOrBlank()) {
            runCatching { api.avatar().unwrap().string().trim() }
                .getOrNull()
                ?.takeIf { it.startsWith("data:") }
                ?.let { _state.value = _state.value.copy(user = user.copy(avatar = it)) }
        }
    }

    /**
     * Regenerating invalidates the old token everywhere it is used, so the replacement is written
     * straight back into the profile - otherwise the app would lock itself out of its own server.
     */
    fun refreshToken() = viewModelScope.launch {
        profiles.awaitReady()
        val active = profiles.activeNow() ?: return@launch
        _state.value = _state.value.copy(busy = true, message = null)
        runCatching { clients.api(active).refreshToken().unwrap().token }
            .onSuccess { fresh ->
                profiles.upsert(active.copy(token = fresh, authenticated = true))
                AppLog.log("profile", "token regenerated for '${active.label}'")
                _state.value = _state.value.copy(
                    busy = false,
                    token = fresh,
                    message = "New token saved. Other devices using the old one must be updated.",
                )
            }
            .onFailure { e ->
                _state.value = _state.value.copy(
                    busy = false,
                    message = (e as? ZiplineException)?.display ?: e.message ?: "Could not refresh",
                )
            }
    }

    /** Signing out of a token client means dropping the stored profile. */
    fun signOut() = viewModelScope.launch {
        profiles.awaitReady()
        profiles.activeNow()?.let { profiles.delete(it.id) }
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }

    // ---------- account settings ----------

    /** Runs [block] against the active server, then reloads so the screen shows server truth. */
    private fun edit(done: String, block: suspend (ZiplineApi) -> Unit) = viewModelScope.launch {
        profiles.awaitReady()
        val active = profiles.activeNow() ?: return@launch
        _state.value = _state.value.copy(busy = true, message = null)
        runCatching { block(clients.api(active)) }.fold(
            onSuccess = {
                _state.value = _state.value.copy(busy = false, message = done)
                load()
            },
            onFailure = { e ->
                _state.value = _state.value.copy(
                    busy = false,
                    message = (e as? ZiplineException)?.display ?: e.message ?: "Request failed",
                )
            },
        )
    }

    fun setAvatar(dataUrl: String) = edit("Avatar updated.") { api ->
        api.patchMe(PatchMeBody(avatar = dataUrl)).unwrap()
    }

    /**
     * Removing needs a real JSON null. `explicitNulls = false` drops nulls from the typed body,
     * which would send `{}` and change nothing - the same trap that made file passwords
     * unclearable.
     */
    fun removeAvatar() = edit("Avatar removed.") { api ->
        api.patchMeRaw(buildJsonObject { put("avatar", JsonNull) }).unwrap()
    }

    fun changeUsername(name: String) = edit("Username changed.") { api ->
        api.patchMe(PatchMeBody(username = name.trim())).unwrap()
    }

    /**
     * The server requires the current password too (E1067 without it, E1066 if wrong) and drops
     * every *other* session on success, which is why the app's own token keeps working.
     */
    fun changePassword(current: String, next: String) = edit("Password changed.") { api ->
        api.patchMe(PatchMeBody(password = next, currentPassword = current)).unwrap()
    }

    fun saveView(view: ZView) = edit("View settings saved.") { api ->
        api.patchMe(PatchMeBody(view = view)).unwrap()
    }

    // ---------- sessions ----------

    fun loadSessions() = viewModelScope.launch {
        profiles.awaitReady()
        val active = profiles.activeNow() ?: return@launch
        _state.value = _state.value.copy(sessionsError = null)
        runCatching { clients.api(active).sessions().unwrap() }.fold(
            onSuccess = { _state.value = _state.value.copy(sessions = it) },
            onFailure = { e ->
                _state.value = _state.value.copy(
                    sessionsError = (e as? ZiplineException)?.display
                        ?: e.message
                        ?: "Could not load sessions",
                )
            },
        )
    }

    private fun dropSessions(body: DeleteSessionBody, done: String) = viewModelScope.launch {
        profiles.awaitReady()
        val active = profiles.activeNow() ?: return@launch
        _state.value = _state.value.copy(busy = true, message = null)
        runCatching { clients.api(active).deleteSession(body).unwrap() }.fold(
            // The response already carries the remaining sessions, so no second round trip.
            onSuccess = {
                AppLog.logAuth(done)
                _state.value = _state.value.copy(busy = false, message = done, sessions = it)
            },
            onFailure = { e ->
                _state.value = _state.value.copy(
                    busy = false,
                    message = (e as? ZiplineException)?.display ?: e.message ?: "Request failed",
                )
            },
        )
    }

    fun logOutSession(id: String) =
        dropSessions(DeleteSessionBody(sessionId = id), "Device signed out.")

    fun logOutOtherSessions() =
        dropSessions(DeleteSessionBody(all = true), "All other devices signed out.")
}
