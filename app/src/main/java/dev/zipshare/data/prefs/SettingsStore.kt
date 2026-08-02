package dev.zipshare.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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

/** Serializable so the whole thing can be exported from Settings in one call. */
@Serializable
data class AppSettings(
    val appLockEnabled: Boolean = false,
    val lockTimeoutSeconds: Int = 60,
    val dynamicColor: Boolean = true,
    val themeMode: String = "system", // system | light | dark
    val partialThresholdMiB: Int = 95,
    val chunkSizeMiB: Int = 16,
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
}

@Singleton
class SettingsStore @Inject constructor(@ApplicationContext private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { it.toSettings() }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setAppLock(enabled: Boolean) = put { it[K_LOCK] = enabled }

    suspend fun setLockTimeout(seconds: Int) = put { it[K_TIMEOUT] = seconds.coerceIn(0, 3600) }

    suspend fun setDynamicColor(enabled: Boolean) = put { it[K_DYNAMIC] = enabled }

    suspend fun setThemeMode(mode: String) = put { it[K_THEME] = mode }

    suspend fun setPartialThreshold(mib: Int) = put { it[K_THRESHOLD] = mib.coerceIn(1, 4096) }

    suspend fun setChunkSize(mib: Int) = put { it[K_CHUNK] = mib.coerceIn(1, 512) }

    suspend fun setDefaultOptions(options: UploadOptions) =
        put { it[K_DEFAULTS] = json.encodeToString(UploadOptions.serializer(), options) }

    suspend fun setSkipUploadSheet(skip: Boolean) = put { it[K_SKIP_SHEET] = skip }

    suspend fun setLinkFormat(format: LinkFormat) = put { it[K_LINK_FORMAT] = format.name }

    suspend fun setNotifyProgress(on: Boolean) = put { it[K_NOTIFY_PROGRESS] = on }

    suspend fun setNotifyComplete(on: Boolean) = put { it[K_NOTIFY_COMPLETE] = on }

    suspend fun setNotifyFailed(on: Boolean) = put { it[K_NOTIFY_FAILED] = on }

    /**
     * E4001 (folder not found): drop the folder from the saved defaults, otherwise every later
     * upload keeps aiming at a folder the server deleted.
     */
    suspend fun clearDefaultUploadFolder() {
        val current = current().defaultOptions
        if (current.folderId != null) setDefaultOptions(current.copy(folderId = null))
    }

    /** Clamped to the server's own 1..100 bound so the request can never be rejected. */
    suspend fun setRecentCount(count: Int) = put { it[K_RECENT_COUNT] = count.coerceIn(1, 100) }

    suspend fun setShowRecents(show: Boolean) = put { it[K_SHOW_RECENTS] = show }

    suspend fun setShowStats(show: Boolean) = put { it[K_SHOW_STATS] = show }

    suspend fun setShowTypes(show: Boolean) = put { it[K_SHOW_TYPES] = show }

    suspend fun setShowLocalHistory(show: Boolean) = put { it[K_SHOW_LOCAL] = show }

    /**
     * Writes a whole [AppSettings] in one edit, for the import in Diagnostic.
     *
     * Goes through the same clamps the individual setters use, because the file being imported is
     * user-editable text - a hand-tweaked chunk size or a negative timeout must not get in just
     * because it arrived as JSON rather than through the UI.
     */
    suspend fun replaceAll(s: AppSettings) = put {
        it[K_LOCK] = s.appLockEnabled
        it[K_TIMEOUT] = s.lockTimeoutSeconds.coerceIn(0, 3600)
        it[K_DYNAMIC] = s.dynamicColor
        it[K_THEME] = if (s.themeMode in setOf("system", "light", "dark")) s.themeMode else "system"
        it[K_THRESHOLD] = s.partialThresholdMiB.coerceIn(1, 4096)
        it[K_CHUNK] = s.chunkSizeMiB.coerceIn(1, 512)
        it[K_DEFAULTS] = json.encodeToString(
            UploadOptions.serializer(),
            // Same reason toSettings() strips it on read: a saved filename would override the
            // name format on every single upload.
            s.defaultOptions.copy(filename = null),
        )
        it[K_SKIP_SHEET] = s.skipUploadSheet
        it[K_LINK_FORMAT] = s.linkFormat.name
        it[K_NOTIFY_PROGRESS] = s.notifyProgress
        it[K_NOTIFY_COMPLETE] = s.notifyComplete
        it[K_NOTIFY_FAILED] = s.notifyFailed
        it[K_RECENT_COUNT] = s.recentCount.coerceIn(1, 100)
        it[K_SHOW_RECENTS] = s.showRecents
        it[K_SHOW_STATS] = s.showStats
        it[K_SHOW_TYPES] = s.showTypes
        it[K_SHOW_LOCAL] = s.showLocalHistory
    }

    private suspend fun put(block: (MutablePreferences) -> Unit) {
        context.settingsDataStore.edit(block)
    }

    private fun Preferences.toSettings() = AppSettings(
        appLockEnabled = this[K_LOCK] ?: false,
        lockTimeoutSeconds = this[K_TIMEOUT] ?: 60,
        dynamicColor = this[K_DYNAMIC] ?: true,
        themeMode = this[K_THEME] ?: "system",
        partialThresholdMiB = this[K_THRESHOLD] ?: 95,
        chunkSizeMiB = this[K_CHUNK] ?: 16,
        // filename is stripped: a fixed name saved as a DEFAULT would silently override the
        // name format on every upload (x-zipline-filename beats x-zipline-format server-side).
        // Stripping on read also heals installs that already persisted one.
        defaultOptions = (
            this[K_DEFAULTS]
                ?.let { runCatching { json.decodeFromString(UploadOptions.serializer(), it) }.getOrNull() }
                ?: UploadOptions.DEFAULT
            ).copy(filename = null),
        skipUploadSheet = this[K_SKIP_SHEET] ?: false,
        // An unrecognised value (hand-edited import, older build) falls back rather than throwing.
        linkFormat = this[K_LINK_FORMAT]
            ?.let { name -> LinkFormat.entries.firstOrNull { it.name == name } }
            ?: LinkFormat.PLAIN,
        notifyProgress = this[K_NOTIFY_PROGRESS] ?: true,
        notifyComplete = this[K_NOTIFY_COMPLETE] ?: true,
        notifyFailed = this[K_NOTIFY_FAILED] ?: true,
        recentCount = this[K_RECENT_COUNT] ?: 10,
        showRecents = this[K_SHOW_RECENTS] ?: true,
        showStats = this[K_SHOW_STATS] ?: true,
        showTypes = this[K_SHOW_TYPES] ?: true,
        showLocalHistory = this[K_SHOW_LOCAL] ?: true,
    )

    private companion object {
        val K_LOCK = booleanPreferencesKey("app_lock")
        val K_TIMEOUT = intPreferencesKey("lock_timeout")
        val K_DYNAMIC = booleanPreferencesKey("dynamic_color")
        val K_THEME = stringPreferencesKey("theme_mode")
        val K_THRESHOLD = intPreferencesKey("partial_threshold_mib")
        val K_CHUNK = intPreferencesKey("chunk_size_mib")
        val K_DEFAULTS = stringPreferencesKey("default_upload_options")
        val K_SKIP_SHEET = booleanPreferencesKey("skip_upload_sheet")
        val K_LINK_FORMAT = stringPreferencesKey("link_format")
        val K_NOTIFY_PROGRESS = booleanPreferencesKey("notify_progress")
        val K_NOTIFY_COMPLETE = booleanPreferencesKey("notify_complete")
        val K_NOTIFY_FAILED = booleanPreferencesKey("notify_failed")
        val K_RECENT_COUNT = intPreferencesKey("recent_count")
        val K_SHOW_RECENTS = booleanPreferencesKey("home_show_recents")
        val K_SHOW_STATS = booleanPreferencesKey("home_show_stats")
        val K_SHOW_TYPES = booleanPreferencesKey("home_show_types")
        val K_SHOW_LOCAL = booleanPreferencesKey("home_show_local")
    }
}
