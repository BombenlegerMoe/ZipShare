package dev.zipshare.ui

import dev.zipshare.data.ProfileRepository
import dev.zipshare.data.model.Profile
import dev.zipshare.data.net.ErrorAction
import dev.zipshare.data.net.ZiplineApi
import dev.zipshare.data.net.ZiplineClients
import dev.zipshare.data.net.ZiplineException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * The server's own words when it gave any, the exception's otherwise.
 *
 * Every failure path in the app shows one of these three, and they were spelled out by hand at
 * each one - so a site that forgot the [ZiplineException] cast silently showed "HTTP 400" where
 * the server had sent a readable reason. [fallback] varies because "Sync failed" and "Upload
 * failed" tell the user more than one generic word for both.
 */
fun Throwable.userMessage(fallback: String = "Request failed"): String =
    (this as? ZiplineException)?.display ?: message ?: fallback

/**
 * Runs [block] against the active server with the bookkeeping every screen-level view model needs:
 * wait for profiles to be read off disk, refuse politely when there is none, raise the loading
 * flag, and turn a failure into a message plus the one side effect that must not be missed - a
 * dead token flagging its profile, or the sign-in prompt never appears.
 *
 * [busy] is how a state class says where its loading flag and error string live. Two view models
 * had this function inline and differed in nothing else, so that pair of fields is the whole of
 * what has to be passed in.
 */
fun <S> MutableStateFlow<S>.callActive(
    scope: CoroutineScope,
    profiles: ProfileRepository,
    clients: ZiplineClients,
    busy: S.(loading: Boolean, error: String?) -> S,
    block: suspend (api: ZiplineApi, profile: Profile) -> Unit,
) {
    scope.launch {
        profiles.awaitReady()
        val active = profiles.activeNow()
        if (active == null) {
            value = value.busy(false, "No server selected.")
            return@launch
        }
        value = value.busy(true, null)
        runCatching { block(clients.api(active), active) }
            .onSuccess { value = value.busy(false, null) }
            .onFailure { e ->
                // Browsing a folder that vanished is not a reason to touch upload defaults; the
                // message is surfaced and that is enough. Only a dead token needs acting on.
                if (e is ZiplineException && e.action == ErrorAction.REAUTH) {
                    profiles.markUnauthenticated(active.id)
                }
                value = value.busy(false, e.userMessage())
            }
    }
}
