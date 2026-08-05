package dev.zipshare.ui.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.zipshare.data.ProfileRepository
import dev.zipshare.data.model.BaseUrl
import dev.zipshare.data.model.Profile
import dev.zipshare.data.model.parseInviteLink
import dev.zipshare.data.net.RegisterBody
import dev.zipshare.data.net.ChainPin
import dev.zipshare.data.net.LoginBody
import dev.zipshare.data.net.PinFetcher
import dev.zipshare.data.net.ZiplineClients
import dev.zipshare.data.net.ZiplineException
import dev.zipshare.data.net.unwrap
import dev.zipshare.data.prefs.SecureStore
import dev.zipshare.ui.userMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TestResult(val ok: Boolean, val message: String)

/** How the profile gets its token. The stored profile is token-based either way. */
enum class AuthMode { TOKEN, PASSWORD, REGISTER }

data class EditState(
    val profile: Profile = Profile(label = "", baseUrl = "", token = ""),
    val testing: Boolean = false,
    val result: TestResult? = null,
    val chain: List<ChainPin> = emptyList(),
    val chainError: String? = null,
    val urlError: String? = null,
    // --- username/password sign-in ---
    val authMode: AuthMode = AuthMode.TOKEN,
    val username: String = "",
    val password: String = "",
    /** True once the server answered {totp:true}: show the code field and retry. */
    val totpRequired: Boolean = false,
    val totpCode: String = "",
    val signingIn: Boolean = false,
    val signInError: String? = null,
    // --- registration from an invite ---
    /** As pasted: either a full `https://host/invite/CODE` link or a bare code. */
    val inviteLink: String = "",
    val inviteCode: String? = null,
)

@HiltViewModel
class ServersViewModel @Inject constructor(
    private val repo: ProfileRepository,
    private val clients: ZiplineClients,
    private val pinFetcher: PinFetcher,
    private val secure: SecureStore,
) : ViewModel() {

    val profiles: StateFlow<List<Profile>> = repo.profiles
    val activeId: StateFlow<String?> = repo.activeId

    private val _edit = MutableStateFlow(EditState())
    val edit: StateFlow<EditState> = _edit

    /**
     * Set when the device's keystore was reset and the encrypted store had to be discarded, taking
     * every saved server with it - so the user is told why their servers are gone instead of
     * meeting an empty list with no explanation.
     *
     * Read off the main thread: the flag lives in its own SharedPreferences, and touching that
     * during ViewModel construction would be disk I/O on the main thread. The banner appearing a
     * frame late costs nothing.
     */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice

    init {
        viewModelScope.launch(Dispatchers.IO) {
            if (secure.consumeKeysetReset()) {
                _notice.value =
                    "This device's secure keystore was reset, so saved servers had to be cleared. " +
                        "Sign in again to restore them."
            }
        }
    }

    fun clearNotice() {
        _notice.value = null
    }

    fun load(profileId: String?) {
        viewModelScope.launch {
            repo.awaitReady()
            val existing = profileId?.let(repo::byId)
            _edit.value =
                EditState(profile = existing ?: Profile(label = "", baseUrl = "", token = ""))
        }
    }

    fun update(profile: Profile) {
        _edit.value = _edit.value.copy(profile = profile, urlError = null, result = null)
    }

    fun setActive(id: String) = repo.setActive(id)

    fun delete(id: String) = repo.delete(id)

    fun save(onDone: () -> Unit) {
        val p = _edit.value.profile
        val url = BaseUrl.parse(p.baseUrl, p.allowCleartext).getOrElse {
            _edit.value = _edit.value.copy(urlError = it.message)
            return
        }
        repo.upsert(
            p.copy(
                baseUrl = url.toString().trimEnd('/'),
                label = p.label.ifBlank { url.host },
                authenticated = true,
            ),
        )
        onDone()
    }

    fun test() {
        val p = _edit.value.profile
        val parsed = BaseUrl.parse(p.baseUrl, p.allowCleartext).getOrElse {
            _edit.value = _edit.value.copy(urlError = it.message)
            return
        }
        val candidate = p.copy(baseUrl = parsed.toString().trimEnd('/'))

        viewModelScope.launch {
            _edit.value = _edit.value.copy(testing = true, result = null)
            val result = runCatching {
                val api = clients.api(candidate)
                val health = api.healthcheck().unwrap()
                if (!health.pass) error("Healthcheck reported a failure (pass=false).")
                val user = api.user().unwrap().user ?: error("No user in /api/user response.")
                val quota = user.quota
                buildString {
                    append("Connected as ${user.username} (${user.role})")
                    if (quota != null) {
                        append(" - quota ")
                        append(
                            when {
                                quota.maxBytes != null -> "${quota.maxBytes} bytes"
                                quota.maxFiles != null -> "${quota.maxFiles} files"
                                else -> "unlimited"
                            },
                        )
                    }
                    runCatching { api.version().unwrap().details?.version }.getOrNull()
                        ?.let { append(" - Zipline $it") }
                }
            }
            // Drop the throwaway client built from the unsaved candidate.
            clients.invalidate(candidate.id)
            _edit.value = _edit.value.copy(
                testing = false,
                result = result.fold(
                    onSuccess = { TestResult(true, it) },
                    onFailure = { e ->
                        TestResult(
                            false,
                            when (e) {
                                // display already carries the server's own error text
                                is ZiplineException -> "HTTP ${e.statusCode} - ${e.display}"
                                else -> e.message ?: "Connection failed"
                            },
                        )
                    },
                ),
            )
        }
    }

    fun setAuthMode(mode: AuthMode) {
        _edit.value = _edit.value.copy(authMode = mode, signInError = null, result = null)
    }

    fun setUsername(value: String) {
        _edit.value = _edit.value.copy(username = value, signInError = null)
    }

    fun setPassword(value: String) {
        _edit.value = _edit.value.copy(password = value, signInError = null)
    }

    fun setTotpCode(value: String) {
        _edit.value = _edit.value.copy(totpCode = value.filter(Char::isDigit).take(6), signInError = null)
    }

    /**
     * Username/password sign-in. Zipline's login opens a *session* rather than returning a token,
     * so the cookie from step one is spent immediately on `/api/user/token`; only that token is
     * kept. The password is never stored.
     *
     * [autoConnect] saves the profile as soon as the token arrives, which is what the first-run
     * screen wants - there is nothing left to decide, so making the user press a second button
     * would just be a step that can only be answered one way. The server editor leaves it off,
     * because there you may still want to set a label or a pin before saving.
     */
    fun signIn(autoConnect: Boolean = false) {
        val s = _edit.value
        val p = s.profile
        val parsed = BaseUrl.parse(p.baseUrl, p.allowCleartext).getOrElse {
            _edit.value = s.copy(urlError = it.message)
            return
        }
        if (s.username.isBlank() || s.password.isBlank()) {
            _edit.value = s.copy(signInError = "Enter a username and password.")
            return
        }
        val candidate = p.copy(baseUrl = parsed.toString().trimEnd('/'))

        viewModelScope.launch {
            _edit.value = _edit.value.copy(signingIn = true, signInError = null)
            val outcome = runCatching {
                val api = clients.loginApi(candidate)
                val login = api.login(
                    LoginBody(
                        username = s.username.trim(),
                        password = s.password,
                        code = s.totpCode.takeIf { it.isNotBlank() },
                    ),
                ).unwrap()
                // totp:true is only sent when a code is still required; a successful login
                // never sets it.
                if (login.totp) return@runCatching null
                api.tokenForSession().unwrap().token
            }

            _edit.value = outcome.fold(
                onSuccess = { token ->
                    when {
                        token == null -> _edit.value.copy(
                            signingIn = false,
                            totpRequired = true,
                            signInError = "This account uses two-factor authentication. " +
                                "Enter the six-digit code.",
                        )

                        else -> _edit.value.copy(
                            signingIn = false,
                            totpRequired = false,
                            // Password is dropped here and never persisted.
                            password = "",
                            totpCode = "",
                            profile = _edit.value.profile.copy(
                                baseUrl = candidate.baseUrl,
                                token = token,
                            ),
                            authMode = AuthMode.TOKEN,
                            // Wording stays neutral: the confirm button is "Save" in the server
                            // editor but "Connect" on the first-run screen.
                            result = TestResult(true, "Signed in - token retrieved."),
                        )
                    }
                },
                onFailure = { e ->
                    _edit.value.copy(
                        signingIn = false,
                        signInError = when (e) {
                            is ZiplineException -> e.display
                            else -> e.message ?: "Sign-in failed"
                        },
                    )
                },
            )

            // Saving a profile is what makes the app leave the sign-in screen, so this has to
            // run after the state above is committed, not inside the fold.
            if (autoConnect && _edit.value.profile.token.isNotBlank()) save {}
        }
    }

    /**
     * Pasting the invite fills the server address too: a Zipline invite link already contains the
     * host, so asking for it a second time would just be a chance to type it wrong.
     */
    fun setInviteLink(value: String) {
        val parsed = parseInviteLink(value)
        val p = _edit.value.profile
        _edit.value = _edit.value.copy(
            inviteLink = value,
            inviteCode = parsed?.code,
            profile = if (parsed?.baseUrl != null) p.copy(baseUrl = parsed.baseUrl) else p,
            urlError = null,
            signInError = null,
        )
    }

    /**
     * Creates an account from an invite and signs straight in as that user - you just chose the
     * credentials, so there is nothing a confirmation step could ask.
     *
     * Deliberately two calls: register, then a normal `/api/auth/login`. Registration *does* leave
     * a session behind, but depending on it made signup fail with "could not fetch token" on real
     * servers - the cookie is `Secure` whenever `core.returnHttpsUrls` is set, so any mismatch
     * between that and how the app reaches the instance (or a proxy that drops it) leaves the
     * account created and unreachable. Logging in explicitly costs one request and uses the exact
     * path ordinary sign-in already proves.
     *
     * No capability probe first: whether the server permits invites or open signup is only
     * knowable from its own answer (E1035/1036/1037), and that answer is better wording than
     * anything guessed up front.
     */
    fun register() {
        val s = _edit.value
        val p = s.profile
        val parsed = BaseUrl.parse(p.baseUrl, p.allowCleartext).getOrElse {
            _edit.value = s.copy(urlError = it.message)
            return
        }
        if (s.username.isBlank() || s.password.isBlank()) {
            _edit.value = s.copy(signInError = "Choose a username and password.")
            return
        }
        val candidate = p.copy(baseUrl = parsed.toString().trimEnd('/'))

        val name = s.username.trim()

        viewModelScope.launch {
            _edit.value = _edit.value.copy(signingIn = true, signInError = null)
            val api = clients.loginApi(candidate)

            val created = runCatching {
                api.register(
                    RegisterBody(
                        username = name,
                        password = s.password,
                        code = s.inviteCode?.takeIf { it.isNotBlank() },
                    ),
                ).unwrap()
            }
            if (created.isFailure) {
                val e = created.exceptionOrNull()
                _edit.value = _edit.value.copy(
                    signingIn = false,
                    signInError = when (e) {
                        is ZiplineException -> e.display
                        else -> e?.message ?: "Registration failed"
                    },
                )
                return@launch
            }

            val token = runCatching {
                val login = api.login(LoginBody(username = name, password = s.password)).unwrap()
                // A brand-new account cannot have TOTP on it, so this branch is a server saying
                // something unexpected rather than a code prompt worth showing.
                if (login.totp) error("Server asked for a two-factor code on a new account.")
                api.tokenForSession().unwrap().token
            }

            _edit.value = token.fold(
                onSuccess = { fresh ->
                    _edit.value.copy(
                        signingIn = false,
                        password = "",
                        profile = _edit.value.profile.copy(
                            baseUrl = candidate.baseUrl,
                            token = fresh,
                        ),
                        authMode = AuthMode.TOKEN,
                        result = TestResult(true, "Account created - signed in as $name."),
                    )
                },
                // The account exists at this point. Sending the user back to a registration form
                // would only earn them "username already taken", so hand them to the sign-in tab
                // with the name already filled instead of leaving them stuck.
                onFailure = { e ->
                    _edit.value.copy(
                        signingIn = false,
                        authMode = AuthMode.PASSWORD,
                        password = "",
                        signInError = "Account \"$name\" was created, but signing in failed: " +
                            e.userMessage("unknown error") +
                            ". Enter your password and press Sign in.",
                    )
                },
            )
            if (_edit.value.profile.token.isNotBlank()) save {}
        }
    }

    fun fetchPins() {
        val p = _edit.value.profile
        val parsed = BaseUrl.parse(p.baseUrl, allowCleartext = false).getOrElse {
            _edit.value = _edit.value.copy(chainError = it.message, chain = emptyList())
            return
        }
        viewModelScope.launch {
            runCatching { pinFetcher.fetch(parsed) }
                .onSuccess { _edit.value = _edit.value.copy(chain = it, chainError = null) }
                .onFailure {
                    _edit.value = _edit.value.copy(chain = emptyList(), chainError = it.message)
                }
        }
    }
}
