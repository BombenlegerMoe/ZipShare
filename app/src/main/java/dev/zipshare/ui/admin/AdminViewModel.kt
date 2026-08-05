package dev.zipshare.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.zipshare.data.ProfileRepository
import dev.zipshare.data.model.Profile
import dev.zipshare.data.net.ApiStats
import dev.zipshare.data.net.CreateInviteBody
import dev.zipshare.data.net.RequerySizeBody
import dev.zipshare.data.net.ThumbnailsBody
import dev.zipshare.data.net.ZInvite
import dev.zipshare.data.net.ZeroFile
import dev.zipshare.data.net.ZiplineApi
import dev.zipshare.data.net.ZiplineClients
import dev.zipshare.data.net.unwrap
import dev.zipshare.ui.callActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/** One editable row in the generic server-settings form. */
data class SettingEntry(
    val key: String,
    val group: String,
    /** Key with the group prefix stripped and split into words: "Delete on max views". */
    val label: String,
    val kind: SettingKind,
    val original: String,
    val current: String,
    val tampered: Boolean,
) {
    val dirty: Boolean get() = original != current
}

/** Acronyms that look wrong under a plain capitalise. */
private val GROUP_NAMES = mapOf(
    "http" to "HTTP",
    "oauth" to "OAuth",
    "mfa" to "MFA",
    "ssl" to "SSL",
    "urls" to "URLs",
)

fun groupTitle(group: String): String =
    GROUP_NAMES[group] ?: group.replaceFirstChar { it.uppercase() }

/**
 * "featuresDeleteOnMaxViews" in group "features" becomes "Delete on max views" - repeating the
 * group name on every row inside it just makes the list harder to scan.
 */
fun settingLabel(key: String, group: String): String {
    val remainder = key.removePrefix(group)
    if (remainder.isEmpty()) return key
    val words = Regex("(?<=[a-z0-9])(?=[A-Z])").split(remainder)
    return words.joinToString(" ") { it.lowercase() }.replaceFirstChar { it.uppercase() }
}

enum class SettingKind { BOOLEAN, NUMBER, TEXT }

data class AdminState(
    val profiles: List<Profile> = emptyList(),
    val active: Profile? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val stats: ApiStats? = null,
    val allTime: Boolean = false,
    val invites: List<ZInvite> = emptyList(),
    val zeroFiles: List<ZeroFile> = emptyList(),
    val settings: List<SettingEntry> = emptyList(),
    val tampered: List<String> = emptyList(),
    val saving: Boolean = false,
)

@HiltViewModel
class AdminViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val clients: ZiplineClients,
) : ViewModel() {

    private val _state = MutableStateFlow(AdminState())
    val state: StateFlow<AdminState> = _state

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

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    fun clearNotice() {
        _state.value = _state.value.copy(notice = null)
    }

    // ---------- metrics ----------

    fun loadStats(allTime: Boolean = _state.value.allTime) = call { api ->
        val stats = api.instanceStats(allTime).unwrap()
        _state.value = _state.value.copy(stats = stats, allTime = allTime)
    }

    // ---------- invites ----------

    fun loadInvites() = call { api ->
        val invites = api.invites().unwrap()
        _state.value = _state.value.copy(invites = invites)
    }

    fun createInvite(expiresAt: String?, maxUses: Int?) = call { api ->
        api.createInvite(CreateInviteBody(expiresAt = expiresAt, maxUses = maxUses)).unwrap()
        _state.value = _state.value.copy(
            invites = api.invites().unwrap(),
            notice = "Invite created.",
        )
    }

    fun deleteInvite(invite: ZInvite) = call { api ->
        api.deleteInvite(invite.id).unwrap()
        _state.value = _state.value.copy(
            invites = _state.value.invites.filterNot { it.id == invite.id },
            notice = "Invite deleted.",
        )
    }

    // ---------- server actions ----------

    fun loadZeroFiles() = call { api ->
        val zeros = api.zeroFiles().unwrap().files
        _state.value = _state.value.copy(zeroFiles = zeros)
    }

    fun clearTemp() = call { api ->
        val status = api.clearTemp().unwrap().status
        _state.value = _state.value.copy(notice = status ?: "Temporary files cleared.")
    }

    fun clearZeros() = call { api ->
        val status = api.clearZeros().unwrap().status
        _state.value = _state.value.copy(
            notice = status ?: "Zero-byte files cleared.",
            zeroFiles = emptyList(),
        )
    }

    fun requerySize(forceDelete: Boolean, forceUpdate: Boolean) = call { api ->
        val status = api.requerySize(RequerySizeBody(forceDelete, forceUpdate)).unwrap().status
        _state.value = _state.value.copy(notice = status ?: "Size re-query started.")
    }

    fun generateThumbnails(rerun: Boolean) = call { api ->
        val status = api.generateThumbnails(ThumbnailsBody(rerun)).unwrap().status
        _state.value = _state.value.copy(notice = status ?: "Thumbnail generation started.")
    }

    // ---------- server settings ----------

    fun loadServerSettings() = call { api ->
        val response = api.serverSettings().unwrap()
        _state.value = _state.value.copy(
            settings = response.settings.toEntries(response.tampered),
            tampered = response.tampered,
        )
    }

    fun editSetting(key: String, value: String) {
        _state.value = _state.value.copy(
            settings = _state.value.settings.map {
                if (it.key == key) it.copy(current = value) else it
            },
        )
    }

    fun resetSettings() {
        _state.value = _state.value.copy(
            settings = _state.value.settings.map { it.copy(current = it.original) },
        )
    }

    /** PATCHes only the fields the user actually changed. */
    fun saveServerSettings() {
        val dirty = _state.value.settings.filter { it.dirty }
        if (dirty.isEmpty()) {
            _state.value = _state.value.copy(notice = "Nothing to save.")
            return
        }
        _state.value = _state.value.copy(saving = true)
        call { api ->
            val body = JsonObject(dirty.associate { it.key to it.toJson() })
            val response = api.patchServerSettings(body).unwrap()
            _state.value = _state.value.copy(
                settings = response.settings.toEntries(response.tampered),
                tampered = response.tampered,
                saving = false,
                notice = "Saved ${dirty.size} setting(s).",
            )
        }
    }

    private fun SettingEntry.toJson(): kotlinx.serialization.json.JsonElement = when (kind) {
        SettingKind.BOOLEAN -> JsonPrimitive(current.toBooleanStrictOrNull() ?: false)
        SettingKind.NUMBER -> JsonPrimitive(current.toDoubleOrNull())
        SettingKind.TEXT -> JsonPrimitive(current)
    }

    private fun JsonObject.toEntries(tampered: List<String>): List<SettingEntry> =
        entries.mapNotNull { (key, element) ->
            val primitive = runCatching { element.jsonPrimitive }.getOrNull() ?: return@mapNotNull null
            val kind = when {
                primitive.booleanOrNull != null -> SettingKind.BOOLEAN
                !primitive.isString && primitive.doubleOrNull != null -> SettingKind.NUMBER
                else -> SettingKind.TEXT
            }
            val text = primitive.content
            val group = key.takeWhile { it.isLowerCase() }.ifEmpty { "other" }
            SettingEntry(
                key = key,
                group = group,
                label = settingLabel(key, group),
                kind = kind,
                original = text,
                current = text,
                tampered = key in tampered,
            )
        }.sortedWith(compareBy({ it.group }, { it.key }))

    /** `saving && l` releases the save spinner on any ending, which only the failure path did. */
    private fun call(block: suspend (ZiplineApi) -> Unit) =
        _state.callActive(
            viewModelScope,
            profiles,
            clients,
            { l, e -> copy(loading = l, error = e, saving = saving && l) },
        ) { api, _ -> block(api) }
}
