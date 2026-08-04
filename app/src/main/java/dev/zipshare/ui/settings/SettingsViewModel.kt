package dev.zipshare.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.zipshare.data.ProfileBackup
import dev.zipshare.data.ProfileRepository
import dev.zipshare.data.db.HistoryDao
import dev.zipshare.data.db.HistoryEntry
import dev.zipshare.data.model.LinkFormat
import dev.zipshare.data.model.UploadOptions
import dev.zipshare.data.net.DisableTotpBody
import dev.zipshare.data.net.EnableTotpBody
import dev.zipshare.data.net.VersionResponse
import dev.zipshare.data.net.ZFolder
import dev.zipshare.data.net.ZiplineApi
import dev.zipshare.data.net.ZiplineClients
import dev.zipshare.data.net.ZiplineException
import dev.zipshare.data.net.unwrap
import dev.zipshare.data.prefs.AppSettings
import dev.zipshare.data.prefs.SettingsStore
import dev.zipshare.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject

/**
 * [qrcode] is the server-rendered `data:image/png;base64,...`; it arrives only while 2FA is off,
 * which is also how [enabled] is known.
 */
data class TotpState(
    val loading: Boolean = false,
    val busy: Boolean = false,
    val enabled: Boolean = false,
    val secret: String? = null,
    val qrcode: String? = null,
    val code: String = "",
    val error: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: SettingsStore,
    private val history: HistoryDao,
    private val profiles: ProfileRepository,
    private val clients: ZiplineClients,
) : ViewModel() {

    private val json = Json { prettyPrint = true; encodeDefaults = true }

    val settings: StateFlow<AppSettings> =
        store.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private val _folders = MutableStateFlow<List<ZFolder>>(emptyList())

    /** Folders of the active server, so a default folder can be chosen here. */
    val folders: StateFlow<List<ZFolder>> = _folders

    private val _serverVersion = MutableStateFlow<VersionResponse?>(null)

    /** What the server reports about itself; null while loading or if the call is refused. */
    val serverVersion: StateFlow<VersionResponse?> = _serverVersion

    init {
        viewModelScope.launch {
            profiles.awaitReady()
            val active = profiles.activeNow() ?: return@launch
            val api = clients.api(active)
            runCatching { api.folders(noIncludeFiles = true).unwrap() }
                .onSuccess { _folders.value = it }
            // Quiet on failure: the version panel is informational, and an older or locked-down
            // instance answering 403 should not put an error in front of the user.
            runCatching { api.version().unwrap() }.onSuccess { _serverVersion.value = it }
        }
    }

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private fun edit(block: (AppSettings) -> AppSettings) = viewModelScope.launch { store.update(block) }

    fun setAppLock(enabled: Boolean) = viewModelScope.launch {
        AppLog.logAuth("app lock ${if (enabled) "enabled" else "disabled"}")
        store.update { it.copy(appLockEnabled = enabled) }
    }

    fun setTimeout(seconds: Int) = edit { it.copy(lockTimeoutSeconds = seconds) }

    fun setDynamicColor(enabled: Boolean) = edit { it.copy(dynamicColor = enabled) }

    fun setThemeMode(mode: String) = edit { it.copy(themeMode = mode) }

    fun setThreshold(mib: Int) = edit { it.copy(partialThresholdMiB = mib) }

    fun setChunkSize(mib: Int) = edit { it.copy(chunkSizeMiB = mib) }

    fun setDefaults(options: UploadOptions) = edit { it.copy(defaultOptions = options) }

    fun setSkipUploadSheet(skip: Boolean) = edit { it.copy(skipUploadSheet = skip) }

    fun setLinkFormat(format: LinkFormat) = edit { it.copy(linkFormat = format) }

    fun setNotifyProgress(on: Boolean) = edit { it.copy(notifyProgress = on) }

    fun setNotifyComplete(on: Boolean) = edit { it.copy(notifyComplete = on) }

    fun setNotifyFailed(on: Boolean) = edit { it.copy(notifyFailed = on) }

    fun clearDefaults() = viewModelScope.launch {
        store.update { it.copy(defaultOptions = UploadOptions.DEFAULT) }
        _message.value = "Upload defaults cleared."
    }

    fun setRecentCount(count: Int) = edit { it.copy(recentCount = count) }

    fun setShowRecents(show: Boolean) = edit { it.copy(showRecents = show) }

    fun setShowStats(show: Boolean) = edit { it.copy(showStats = show) }

    fun setShowTypes(show: Boolean) = edit { it.copy(showTypes = show) }

    fun setShowLocalHistory(show: Boolean) = edit { it.copy(showLocalHistory = show) }

    fun clearMessage() {
        _message.value = null
    }

    // ---------- two-factor authentication ----------

    private val _totp = MutableStateFlow(TotpState())
    val totp: StateFlow<TotpState> = _totp

    /**
     * Loads the secret and, while 2FA is still off, the server's own QR image. Whether it is on is
     * read from that image's presence rather than a user field, because the server only withholds
     * the QR once a secret is active.
     */
    fun loadTotp() = viewModelScope.launch {
        _totp.value = _totp.value.copy(loading = true, error = null)
        val active = profiles.activeNow()
        if (active == null) {
            _totp.value = TotpState(error = "No server selected.")
            return@launch
        }
        runCatching { clients.api(active).totpSetup().unwrap() }.fold(
            onSuccess = {
                _totp.value = TotpState(
                    secret = it.secret,
                    qrcode = it.qrcode,
                    enabled = it.qrcode == null,
                )
            },
            onFailure = { e -> _totp.value = TotpState(error = errorText(e)) },
        )
    }

    fun setTotpCode(value: String) {
        _totp.value = _totp.value.copy(code = value.filter(Char::isDigit).take(6), error = null)
    }

    fun enableTotp() = totpCall("Two-factor authentication is on.") { api, s ->
        api.enableTotp(EnableTotpBody(code = s.code, secret = s.secret.orEmpty())).unwrap()
    }

    fun disableTotp() = totpCall("Two-factor authentication is off.") { api, s ->
        api.disableTotp(DisableTotpBody(code = s.code)).unwrap()
    }

    /**
     * Both directions need the same six digits, the same failure handling and the same reload
     * afterwards - the server is the only thing that knows the new state.
     */
    private fun totpCall(
        done: String,
        block: suspend (ZiplineApi, TotpState) -> Unit,
    ) = viewModelScope.launch {
        val s = _totp.value
        if (s.code.length != 6) {
            _totp.value = s.copy(error = "Enter the six-digit code from your authenticator.")
            return@launch
        }
        val active = profiles.activeNow() ?: return@launch
        _totp.value = s.copy(busy = true, error = null)
        runCatching { block(clients.api(active), s) }.fold(
            onSuccess = {
                AppLog.logAuth(done)
                _message.value = done
                _totp.value = TotpState()
                loadTotp()
            },
            onFailure = { e -> _totp.value = _totp.value.copy(busy = false, error = errorText(e)) },
        )
    }

    private fun errorText(e: Throwable) = when (e) {
        is ZiplineException -> e.display
        else -> e.message ?: "Request failed"
    }

    fun clearHistory() = viewModelScope.launch {
        history.clear()
        _message.value = "History cleared."
    }

    /** Writes an export into the FileProvider-shareable cache dir; [text] runs on IO. */
    private suspend fun writeExport(name: String, text: () -> String): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "export").apply { mkdirs() }
            File(dir, name).apply { writeText(text()) }
        }

    fun exportHistory(onFile: (File) -> Unit) = viewModelScope.launch {
        val rows = history.all()
        val file = writeExport("zipshare-history-${System.currentTimeMillis()}.json") {
            json.encodeToString(ListSerializer(HistoryEntry.serializer()), rows)
        }
        _message.value = "Exported ${rows.size} rows to ${file.name}"
        onFile(file)
    }

    /**
     * Every app setting as JSON. Deliberately not the server profiles: those hold API tokens and
     * certificate pins, and an export lands in the share sheet where it can go anywhere. Upload
     * defaults are included, minus the default password for the same reason.
     */
    fun exportSettings(onFile: (File) -> Unit) = viewModelScope.launch {
        val current = store.current()
        val safe = current.copy(defaultOptions = current.defaultOptions.copy(password = null))
        val file = writeExport("zipshare-settings-${System.currentTimeMillis()}.json") {
            json.encodeToString(AppSettings.serializer(), safe)
        }
        _message.value = "Settings exported as ${file.name}"
        onFile(file)
    }

    /**
     * Reads a settings export back in. Lenient on purpose: a file written by a different version
     * of the app may carry fields this build does not know, and those should be skipped rather
     * than fail the whole import. Anything that is not a settings file at all is reported as such.
     */
    fun importSettings(uri: Uri) = viewModelScope.launch {
        val parsed = runCatching {
            val text = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                    ?: error("could not open the file")
            }
            Json { ignoreUnknownKeys = true; isLenient = true }
                .decodeFromString(AppSettings.serializer(), text)
        }
        parsed.fold(
            onSuccess = {
                store.replaceAll(it)
                AppLog.log("settings", "settings imported from file")
                _message.value = "Settings imported."
            },
            onFailure = {
                _message.value = "That file is not a ZipShare settings export."
            },
        )
    }

    /**
     * Every server profile, encrypted under [password]. This is the one export that carries API
     * tokens, which is exactly why it is encrypted and why the settings export still excludes them.
     *
     * The work is off the main thread because PBKDF2 at 210k iterations deliberately takes a
     * noticeable fraction of a second.
     */
    fun exportProfiles(password: String, onFile: (File) -> Unit) = viewModelScope.launch {
        profiles.awaitReady()
        val list = profiles.profiles.value
        if (list.isEmpty()) {
            _message.value = "There are no servers to back up."
            return@launch
        }
        val text = withContext(Dispatchers.IO) { ProfileBackup.encrypt(list, password) }
        val file = writeExport("zipshare-servers-${System.currentTimeMillis()}.json") { text }
        // Count only - never a label or url, which would defeat encrypting the file.
        AppLog.log("profiles", "exported ${list.size} profile(s), encrypted")
        _message.value = "${list.size} server(s) exported as ${file.name}"
        onFile(file)
    }

    /**
     * Restores a profile backup. Imported servers are added alongside the existing ones rather than
     * replacing them: this runs on a device that may already be set up, and silently dropping a
     * working server to honour a file would be the worse mistake.
     */
    fun importProfiles(uri: Uri, password: String) = viewModelScope.launch {
        val result = runCatching {
            val text = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                    ?: error("could not open the file")
            }
            withContext(Dispatchers.IO) { ProfileBackup.decrypt(text, password) }
        }
        result.fold(
            onSuccess = { imported ->
                profiles.awaitReady()
                val added = ProfileBackup.toAdd(profiles.profiles.value, imported)
                // A fresh id: the backup's id may already belong to a different profile here.
                added.forEach { profiles.upsert(it.copy(id = java.util.UUID.randomUUID().toString())) }
                AppLog.log("profiles", "imported ${added.size} of ${imported.size} profile(s)")
                _message.value = when {
                    added.isEmpty() -> "Those servers are already set up."
                    else -> "Added ${added.size} server(s)."
                }
            },
            onFailure = { e ->
                _message.value = when (e) {
                    is ProfileBackup.WrongPasswordException -> "Wrong password, or the file was changed."
                    is ProfileBackup.NotABackupException -> "That file is not a ZipShare server backup."
                    else -> "Could not read that file."
                }
            },
        )
    }

    /** Decrypts the on-device log and hands the clear-text file to the share sheet. */
    fun exportLog(onFile: (File) -> Unit) = viewModelScope.launch {
        val file = writeExport("zipshare-log-${System.currentTimeMillis()}.txt", AppLog::export)
        _message.value = "Log exported as ${file.name}"
        onFile(file)
    }

    fun clearLog() = viewModelScope.launch {
        withContext(Dispatchers.IO) { AppLog.clear() }
        _message.value = "Log cleared."
    }

    fun exportAuthLog(onFile: (File) -> Unit) = viewModelScope.launch {
        val file = writeExport(
            "zipshare-login-log-${System.currentTimeMillis()}.txt",
            AppLog::exportAuth,
        )
        _message.value = "Login log exported as ${file.name}"
        onFile(file)
    }

    fun clearAuthLog() = viewModelScope.launch {
        withContext(Dispatchers.IO) { AppLog.clearAuth() }
        _message.value = "Login log cleared."
    }
}
