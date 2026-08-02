package dev.zipshare.ui.upload

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.zipshare.data.ProfileRepository
import dev.zipshare.data.db.HistoryDao
import dev.zipshare.data.db.HistoryEntry
import dev.zipshare.data.model.Profile
import dev.zipshare.data.net.shareUrl
import dev.zipshare.data.net.ErrorAction
import dev.zipshare.data.net.UploadHeaderBuilder
import dev.zipshare.data.net.ZiplineClients
import dev.zipshare.data.net.ZiplineException
import dev.zipshare.data.net.unwrap
import dev.zipshare.data.prefs.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject

data class UploadTextState(
    val profiles: List<Profile> = emptyList(),
    val active: Profile? = null,
    val text: String = "",
    val extension: String = "txt",
    val mime: String = "text/plain",
    val uploading: Boolean = false,
    val resultUrl: String? = null,
    val error: String? = null,
) {
    val byteCount: Int get() = text.toByteArray(Charsets.UTF_8).size
}

/**
 * Text snippets go straight to /api/upload rather than through WorkManager: they are tiny, and the
 * user is waiting on the resulting link, so a foreground request with inline feedback fits better.
 */
@HiltViewModel
class UploadTextViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val clients: ZiplineClients,
    private val settings: SettingsStore,
    private val history: HistoryDao,
) : ViewModel() {

    data class Language(val ext: String, val mime: String)

    private val _state = MutableStateFlow(UploadTextState())
    val state: StateFlow<UploadTextState> = _state

    init {
        viewModelScope.launch {
            profiles.profiles.collect { _state.value = _state.value.copy(profiles = it) }
        }
        viewModelScope.launch {
            profiles.active
                .distinctUntilChanged { a, b -> a?.id == b?.id }
                .collect { _state.value = _state.value.copy(active = it) }
        }
    }

    fun selectProfile(id: String) = profiles.setActive(id)

    fun setText(value: String) {
        _state.value = _state.value.copy(text = value, resultUrl = null)
    }

    fun setLanguage(language: Language) {
        _state.value = _state.value.copy(extension = language.ext, mime = language.mime)
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    fun upload() {
        val s = _state.value
        if (s.text.isBlank()) return
        viewModelScope.launch {
            profiles.awaitReady()
            val active = profiles.activeNow() ?: run {
                _state.value = _state.value.copy(error = "No server selected.")
                return@launch
            }
            _state.value = _state.value.copy(uploading = true, error = null, resultUrl = null)

            runCatching {
                val options = settings.current().defaultOptions
                val body = s.text.toByteArray(Charsets.UTF_8)
                    .toRequestBody(s.mime.toMediaTypeOrNull())
                val part = MultipartBody.Part.createFormData("file", "text.${s.extension}", body)
                clients.api(active)
                    .upload(UploadHeaderBuilder.build(options), listOf(part))
                    .unwrap()
            }.onSuccess { response ->
                val file = response.files.firstOrNull()
                // Recording it here keeps the on-device history complete and is what tells the
                // dashboard to re-sync.
                if (file != null) {
                    history.insert(
                        HistoryEntry(
                            remoteId = file.id,
                            profileId = active.id,
                            localUri = null,
                            remoteUrl = file.shareUrl(active.baseUrl),
                            name = file.name,
                            mime = file.type,
                            size = s.byteCount.toLong(),
                            deletesAt = response.deletesAt,
                            ts = System.currentTimeMillis(),
                            pending = file.pending == true,
                        ),
                    )
                }
                _state.value = _state.value.copy(
                    uploading = false,
                    // The server returns a route-relative url; a link that only works inside this
                    // app is useless the moment it is copied anywhere.
                    resultUrl = file?.shareUrl(active.baseUrl),
                    error = if (file == null) "Server returned no file." else null,
                )
            }.onFailure { e ->
                if (e is ZiplineException && e.action == ErrorAction.REAUTH) {
                    profiles.markUnauthenticated(active.id)
                }
                _state.value = _state.value.copy(
                    uploading = false,
                    error = (e as? ZiplineException)?.display ?: e.message ?: "Upload failed",
                )
            }
        }
    }

    companion object {
        val LANGUAGES = listOf(
            Language("txt", "text/plain"),
            Language("md", "text/markdown"),
            Language("json", "application/json"),
            Language("js", "text/javascript"),
            Language("ts", "text/typescript"),
            Language("py", "text/x-python"),
            Language("kt", "text/x-kotlin"),
            Language("java", "text/x-java"),
            Language("html", "text/html"),
            Language("css", "text/css"),
            Language("sh", "application/x-sh"),
            Language("yaml", "text/yaml"),
            Language("xml", "text/xml"),
            Language("sql", "application/sql"),
            Language("rs", "text/rust"),
            Language("go", "text/x-go"),
            Language("c", "text/x-c"),
            Language("log", "text/plain"),
        )
    }
}
