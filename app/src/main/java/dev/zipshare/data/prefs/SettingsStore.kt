package dev.zipshare.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.zipshare.data.model.LinkFormat
import dev.zipshare.data.model.UploadOptions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore by preferencesDataStore(name = "zipshare_settings")

private val THEMES = setOf("system", "light", "dark")
private val COMPRESSION_FORMATS = setOf("webp", "jpeg")

/** Serializable so the whole thing can be exported from Settings in one call. */
@Serializable
data class AppSettings(
    val appLockEnabled: Boolean = false,
    val lockTimeoutSeconds: Int = 60,
    val dynamicColor: Boolean = true,
    val themeMode: String = "system", // system | light | dark
    val partialThresholdMiB: Int = 95,
    val chunkSizeMiB: Int = 16,
    // --- on-device image compression ---
    /** Off by default: it is lossy, and silently degrading someone's photos is not a default. */
    val deviceCompression: Boolean = false,
    /** [dev.zipshare.upload.ImageCompressor.WEBP] or `JPEG`. */
    val deviceCompressionFormat: String = "webp",
    val deviceCompressionQuality: Int = 85,
    val defaultOptions: UploadOptions = UploadOptions.DEFAULT,
    /** Upload straight away with the saved defaults instead of showing the options sheet. */
    val skipUploadSheet: Boolean = false,
    /** What every "Copy link" button puts on the clipboard. */
    val linkFormat: LinkFormat = LinkFormat.PLAIN,
    // --- notifications ---
    /** Detail in the ongoing upload notification. Android requires *a* notification either way. */
    val notifyProgress: Boolean = true,
    val notifyComplete: Boolean = true,
    val notifyFailed: Boolean = true,
    // --- dashboard ---
    /** How many recent uploads to pull from /api/user/recent. Server clamps to 1..100. */
    val recentCount: Int = 10,
    val showRecents: Boolean = true,
    val showStats: Boolean = true,
    val showTypes: Boolean = true,
    val showLocalHistory: Boolean = true,
) {
    val partialThresholdBytes: Long get() = partialThresholdMiB.toLong() * 1024 * 1024
    val chunkSizeBytes: Long get() = chunkSizeMiB.toLong() * 1024 * 1024

    /**
     * Every bound in one place, applied on every write and every read.
     *
     * An imported file is user-editable text, so a hand-tweaked chunk size or a negative timeout
     * must not get in just because it arrived as JSON rather than through the UI - and the UI and
     * the importer cannot be allowed to disagree about what the limits are.
     */
    fun sanitised() = copy(
        lockTimeoutSeconds = lockTimeoutSeconds.coerceIn(0, 3600),
        themeMode = if (themeMode in THEMES) themeMode else "system",
        partialThresholdMiB = partialThresholdMiB.coerceIn(1, 4096),
        chunkSizeMiB = chunkSizeMiB.coerceIn(1, 512),
        deviceCompressionFormat = if (deviceCompressionFormat in COMPRESSION_FORMATS) {
            deviceCompressionFormat
        } else {
            "webp"
        },
        // 0 would upload an unreadable smear, 100 is larger than the original for no gain.
        deviceCompressionQuality = deviceCompressionQuality.coerceIn(30, 100),
        recentCount = recentCount.coerceIn(1, 100),
        // A fixed name saved as a DEFAULT would silently override the name format on every upload
        // (x-zipline-filename beats x-zipline-format server-side). Stripping on read also heals
        // installs that already persisted one.
        defaultOptions = defaultOptions.copy(filename = null),
    )
}

@Singleton
class SettingsStore @Inject constructor(@ApplicationContext private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { it.read() }

    suspend fun current(): AppSettings = settings.first()

    /**
     * The only write path. Read-modify-write inside a single [edit] so two rapid toggles cannot
     * lose one, and [AppSettings.sanitised] is the one place any bound is enforced.
     */
    suspend fun update(block: (AppSettings) -> AppSettings) {
        context.settingsDataStore.edit { prefs ->
            prefs[K_JSON] = json.encodeToString(
                AppSettings.serializer(),
                block(prefs.read()).sanitised(),
            )
        }
    }

    /** Whole-object write, for the settings import in Diagnostic. */
    suspend fun replaceAll(s: AppSettings) = update { s }

    /**
     * E4001 (folder not found): drop the folder from the saved defaults, otherwise every later
     * upload keeps aiming at a folder the server deleted.
     */
    suspend fun clearDefaultUploadFolder() {
        if (current().defaultOptions.folderId == null) return
        update { it.copy(defaultOptions = it.defaultOptions.copy(folderId = null)) }
    }

    private fun Preferences.read(): AppSettings =
        this[K_JSON]
            ?.let { runCatching { json.decodeFromString(AppSettings.serializer(), it) }.getOrNull() }
            ?.sanitised()
            ?: legacy()

    /**
     * Reads the 1.0 layout, which stored one preference key per setting. Installs upgrading from
     * 1.0 would otherwise silently reset - including app lock, which must never quietly turn
     * itself off. Keys are matched by name rather than by typed [Preferences.Key] so this stays a
     * single block that can be deleted once no 1.0 installs remain.
     */
    private fun Preferences.legacy(): AppSettings {
        val old = asMap().mapKeys { it.key.name }
        if (old.isEmpty()) return AppSettings()
        fun flag(name: String, fallback: Boolean) = old[name] as? Boolean ?: fallback
        fun int(name: String, fallback: Int) = old[name] as? Int ?: fallback
        return AppSettings(
            appLockEnabled = flag("app_lock", false),
            lockTimeoutSeconds = int("lock_timeout", 60),
            dynamicColor = flag("dynamic_color", true),
            themeMode = old["theme_mode"] as? String ?: "system",
            partialThresholdMiB = int("partial_threshold_mib", 95),
            chunkSizeMiB = int("chunk_size_mib", 16),
            defaultOptions = (old["default_upload_options"] as? String)
                ?.let { runCatching { json.decodeFromString(UploadOptions.serializer(), it) }.getOrNull() }
                ?: UploadOptions.DEFAULT,
            skipUploadSheet = flag("skip_upload_sheet", false),
            linkFormat = (old["link_format"] as? String)
                ?.let { name -> LinkFormat.entries.firstOrNull { it.name == name } }
                ?: LinkFormat.PLAIN,
            notifyProgress = flag("notify_progress", true),
            notifyComplete = flag("notify_complete", true),
            notifyFailed = flag("notify_failed", true),
            recentCount = int("recent_count", 10),
            showRecents = flag("home_show_recents", true),
            showStats = flag("home_show_stats", true),
            showTypes = flag("home_show_types", true),
            showLocalHistory = flag("home_show_local", true),
        ).sanitised()
    }

    private companion object {
        val K_JSON = stringPreferencesKey("settings_json")
    }
}
