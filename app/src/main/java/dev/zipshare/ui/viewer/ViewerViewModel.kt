package dev.zipshare.ui.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.zipshare.data.ProfileRepository
import dev.zipshare.data.net.ZiplineClients
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import javax.inject.Inject

/**
 * Supplies the active profile's HTTP client to the player, so playback inherits the token header,
 * TLS policy and pin. Null until profiles have loaded off disk - the viewer shows a spinner.
 */
@HiltViewModel
class ViewerViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val clients: ZiplineClients,
) : ViewModel() {

    private val _client = MutableStateFlow<OkHttpClient?>(null)
    val client: StateFlow<OkHttpClient?> = _client

    init {
        viewModelScope.launch {
            profiles.awaitReady()
            val active = profiles.activeNow() ?: return@launch
            _client.value = clients.client(active)
        }
    }
}
