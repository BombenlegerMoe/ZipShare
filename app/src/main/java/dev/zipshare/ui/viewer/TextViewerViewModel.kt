package dev.zipshare.ui.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.zipshare.data.ProfileRepository
import dev.zipshare.data.db.HistoryDao
import dev.zipshare.data.db.HistoryEntry
import dev.zipshare.data.net.shareUrl
import dev.zipshare.data.net.UploadHeaderBuilder
import dev.zipshare.data.net.ZiplineClients
import dev.zipshare.data.net.ZiplineException
import dev.zipshare.data.net.unwrap
import dev.zipshare.data.prefs.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import javax.inject.Inject

/**
 * Reads at most [maxBytes] from [source], or returns null when the source holds more than that.
 *
 * `Content-Length` cannot be used for this decision: it is -1 whenever the response is chunked,
 * which is the normal case for a dynamically served file, and `-1 > maxBytes` is false - so a size
 * check on it lets exactly the oversized responses through that it exists to stop. Asking the
 * source for one byte more than the limit answers the question from what actually arrived, and
 * leaves the rest of an oversized body unread on the socket.
 */
internal fun readCapped(source: BufferedSource, maxBytes: Long): String? {
    source.request(maxBytes + 1)
    return if (source.buffer.size > maxBytes) null else source.buffer.readUtf8()
}

data class TextViewerState(
    val loading: Boolean = true,
    val original: String = "",
    val text: String = "",
    val error: String? = null,
    val saving: Boolean = false,
    val savedUrl: String? = null,
    val tooLarge: Boolean = false,
) {
    val dirty: Boolean get() = text != original
}

/**
 * Loads a text file's contents for viewing and editing.
 *
 * Zipline has no endpoint that replaces a file's bytes - its own dashboard only edits metadata -
 * so saving uploads the edited text as a NEW file. The screen says so rather than pretending an
 * in-place save happened.
 */
@HiltViewModel
class TextViewerViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val clients: ZiplineClients,
    private val settings: SettingsStore,
    private val history: HistoryDao,
) : ViewModel() {

    private val _state = MutableStateFlow(TextViewerState())
    val state: StateFlow<TextViewerState> = _state

    fun load(url: String) = viewModelScope.launch {
        profiles.awaitReady()
        val active = profiles.activeNow() ?: run {
            _state.value = _state.value.copy(loading = false, error = "No server selected.")
            return@launch
        }
        _state.value = _state.value.copy(loading = true, error = null)

        val result = withContext(Dispatchers.IO) {
            runCatching {
                clients.client(active).newCall(Request.Builder().url(url).build()).execute()
                    .use { response ->
                        if (!response.isSuccessful) error("HTTP ${response.code}")
                        val body = response.body ?: error("Empty response")
                        readCapped(body.source(), MAX_BYTES)
                    }
            }
        }

        _state.value = result.fold(
            onSuccess = { content ->
                if (content == null) {
                    _state.value.copy(loading = false, tooLarge = true)
                } else {
                    _state.value.copy(loading = false, original = content, text = content)
                }
            },
            onFailure = { e ->
                _state.value.copy(
                    loading = false,
                    error = (e as? ZiplineException)?.display ?: e.message ?: "Could not load",
                )
            },
        )
    }

    fun setText(value: String) {
        _state.value = _state.value.copy(text = value, savedUrl = null)
    }

    fun revert() {
        _state.value = _state.value.copy(text = _state.value.original, savedUrl = null)
    }

    /** Uploads the edited text as a new file, mirroring the text-snippet upload path. */
    fun saveAsNew(name: String, mime: String) = viewModelScope.launch {
        val s = _state.value
        profiles.awaitReady()
        val active = profiles.activeNow() ?: return@launch
        _state.value = s.copy(saving = true, error = null, savedUrl = null)

        runCatching {
            val options = settings.current().defaultOptions
            val body = s.text.toByteArray(Charsets.UTF_8).toRequestBody(mime.toMediaTypeOrNull())
            val part = MultipartBody.Part.createFormData("file", name, body)
            clients.api(active).upload(UploadHeaderBuilder.build(options), listOf(part)).unwrap()
        }.onSuccess { response ->
            val file = response.files.firstOrNull()
            if (file != null) {
                history.insert(
                    HistoryEntry(
                        remoteId = file.id,
                        profileId = active.id,
                        localUri = null,
                        remoteUrl = file.shareUrl(active.baseUrl),
                        name = file.name,
                        mime = file.type,
                        size = s.text.toByteArray(Charsets.UTF_8).size.toLong(),
                        deletesAt = response.deletesAt,
                        ts = System.currentTimeMillis(),
                        pending = file.pending == true,
                    ),
                )
            }
            _state.value = _state.value.copy(
                saving = false,
                savedUrl = file?.url,
                original = _state.value.text,
                error = if (file == null) "Server returned no file." else null,
            )
        }.onFailure { e ->
            _state.value = _state.value.copy(
                saving = false,
                error = (e as? ZiplineException)?.display ?: e.message ?: "Save failed",
            )
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    private companion object {
        const val MAX_BYTES = 2L * 1024 * 1024
    }
}
