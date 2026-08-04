package dev.zipshare.ui.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.zipshare.data.model.LinkFormat
import dev.zipshare.data.model.UploadOptions
import dev.zipshare.security.Biometrics
import dev.zipshare.ui.FocusTarget
import dev.zipshare.ui.search.SearchAction
import dev.zipshare.ui.shareFile
import dev.zipshare.upload.ImageCompressor
import dev.zipshare.ui.upload.UploadOptionsForm
import java.io.File
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onMenu: () -> Unit,
    username: String? = null,
    /** Row id from search: scroll to it and flash it on arrival. */
    focus: String? = null,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val folders by vm.folders.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val canAuthenticate = remember {
        BiometricManager.from(context).canAuthenticate(Biometrics.AUTHENTICATORS) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }
    // Re-read on resume: the user may have changed it in Android settings and come back.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var notificationsAllowed by remember { mutableStateOf(true) }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            notificationsAllowed = NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }

    // Once the user edits, the draft owns the UI. Binding fields straight to the DataStore flow
    // made every keystroke round-trip through disk, which reset the caret mid-word
    // ("myname" -> "ynamem") and made numeric fields impossible to clear.
    var draft by remember { mutableStateOf<UploadOptions?>(null) }
    val options = draft ?: s.defaultOptions

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.clearMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onMenu) { Icon(Icons.Filled.Menu, "Menu") }
                },
                // These two use a plain TopAppBar rather than ShellTopBar, so search is added
                // here explicitly to keep it in the same slot on every screen.
                actions = { SearchAction() },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Security", style = MaterialTheme.typography.titleMedium)
            FocusTarget("app_lock", focus) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = s.appLockEnabled, onCheckedChange = vm::setAppLock)
                    Text("Require biometric / device credential", Modifier.padding(start = 8.dp))
                }
            }
            // Without an enrolled credential the lock silently never engages, which is worse than
            // not offering it - say so instead of implying the app is protected.
            if (s.appLockEnabled && !canAuthenticate) {
                Text(
                    "This device has no screen lock or biometric enrolled, so the app will not " +
                        "actually lock. Set a PIN, pattern or fingerprint in system settings first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            FocusTarget("lock_after", focus) {
                NumberSetting(
                    initial = s.lockTimeoutSeconds,
                    label = "Lock after (seconds in background)",
                    enabled = s.appLockEnabled,
                    onCommit = vm::setTimeout,
                )
            }

            HorizontalDivider()

            FocusTarget("totp", focus) { TotpSection(username = username, vm = vm) }

            HorizontalDivider()
            Text("Dashboard", style = MaterialTheme.typography.titleMedium)
            FocusTarget("recent_count", focus) {
                NumberSetting(
                    initial = s.recentCount,
                    label = "Recent uploads to sync",
                    supporting = "1-100. Pulled from /api/user/recent on each refresh.",
                    enabled = s.showRecents,
                    onCommit = vm::setRecentCount,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(3, 5, 10, 20, 50).forEach { n ->
                    FilterChip(
                        selected = s.recentCount == n,
                        onClick = { vm.setRecentCount(n) },
                        label = { Text("$n") },
                        enabled = s.showRecents,
                    )
                }
            }
            FocusTarget("show_recents", focus) { ToggleRow("Show recent files", s.showRecents, vm::setShowRecents) }
            FocusTarget("show_stats", focus) { ToggleRow("Show stat cards", s.showStats, vm::setShowStats) }
            FocusTarget("show_types", focus) { ToggleRow("Show file types table", s.showTypes, vm::setShowTypes) }
            FocusTarget("show_local", focus) { ToggleRow("Show on-device history", s.showLocalHistory, vm::setShowLocalHistory) }

            HorizontalDivider()
            Text("Notifications", style = MaterialTheme.typography.titleMedium)
            if (!notificationsAllowed) {
                Text(
                    "Notifications are turned off for ZipShare in Android settings, so none of " +
                        "these will appear.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedButton(onClick = { openNotificationSettings(context) }) {
                    Text("Open Android notification settings")
                }
            }
            FocusTarget("notifications", focus) { ToggleRow("Upload progress details", s.notifyProgress, vm::setNotifyProgress) }
            Text(
                "Android requires an ongoing notification while uploading. Turning this off " +
                    "keeps it, but hides the file name and percentage.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ToggleRow("Upload completed", s.notifyComplete, vm::setNotifyComplete)
            ToggleRow("Upload failed", s.notifyFailed, vm::setNotifyFailed)

            HorizontalDivider()
            Text("Appearance", style = MaterialTheme.typography.titleMedium)
            FocusTarget("theme", focus) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("system", "light", "dark").forEach { mode ->
                    FilterChip(
                        selected = s.themeMode == mode,
                        onClick = { vm.setThemeMode(mode) },
                        label = { Text(mode) },
                    )
                }
            }
            }
            FocusTarget("dynamic_color", focus) { ToggleRow("Dynamic color", s.dynamicColor, vm::setDynamicColor) }

            HorizontalDivider()
            Text("Sharing", style = MaterialTheme.typography.titleMedium)
            FocusTarget("sharing", focus) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "What the Copy link buttons put on the clipboard.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = s.linkFormat == LinkFormat.PLAIN,
                    onClick = { vm.setLinkFormat(LinkFormat.PLAIN) },
                    label = { Text("Plain link") },
                )
                FilterChip(
                    selected = s.linkFormat == LinkFormat.MARKDOWN,
                    onClick = { vm.setLinkFormat(LinkFormat.MARKDOWN) },
                    label = { Text("Markdown") },
                )
                FilterChip(
                    selected = s.linkFormat == LinkFormat.VIEW,
                    onClick = { vm.setLinkFormat(LinkFormat.VIEW) },
                    label = { Text("View page") },
                )
            }
            Text(
                when (s.linkFormat) {
                    LinkFormat.PLAIN ->
                        "Chat apps show the URL, with a preview underneath."
                    LinkFormat.MARKDOWN ->
                        "Discord, Slack and GitHub show only the file name, and no preview."
                    LinkFormat.VIEW ->
                        "Links to your server's view page, so chat apps build a rich embed from " +
                            "the Viewing files settings below. Needs \"Enable view routes\" and " +
                            "\"Enable embed\" turned on in Account settings."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                when (s.linkFormat) {
                    LinkFormat.PLAIN -> "https://your-server/u/holiday.png"
                    LinkFormat.MARKDOWN -> "[holiday.png](https://your-server/u/holiday.png)"
                    LinkFormat.VIEW -> "https://your-server/view/holiday.png"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            }
            }

            HorizontalDivider()
            Text("Chunked upload (this device)", style = MaterialTheme.typography.titleMedium)
            Text(
                "How this app splits a large upload. The server has its own Chunks settings " +
                    "under Administrator - those are for the web dashboard and do not affect " +
                    "uploads from here. The only server-side limit that applies to both is the " +
                    "maximum file size.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FocusTarget("chunked", focus) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberSetting(
                        initial = s.partialThresholdMiB,
                        label = "Use chunked upload above (MiB)",
                        onCommit = vm::setThreshold,
                    )
                    NumberSetting(
                        initial = s.chunkSizeMiB,
                        label = "Chunk size (MiB)",
                        onCommit = vm::setChunkSize,
                    )
                }
            }

            HorizontalDivider()
            Text("Compress images on this device", style = MaterialTheme.typography.titleMedium)
            Text(
                "Re-encodes photos before uploading, so less goes over mobile data. This is not " +
                    "the same as the server's Image compression below: that one shrinks the file " +
                    "after it arrives, so the upload itself is still full size. Turning this on " +
                    "skips the server's compression for those files, to avoid re-encoding twice.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FocusTarget("device_compression", focus) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToggleRow("Compress before uploading", s.deviceCompression, vm::setDeviceCompression)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(ImageCompressor.WEBP to "WebP", ImageCompressor.JPEG to "JPEG").forEach { (v, label) ->
                            FilterChip(
                                selected = s.deviceCompressionFormat == v,
                                onClick = { vm.setDeviceCompressionFormat(v) },
                                label = { Text(label) },
                                enabled = s.deviceCompression,
                            )
                        }
                    }
                    Text(
                        "Quality ${s.deviceCompressionQuality}" +
                            if (s.deviceCompressionFormat == ImageCompressor.WEBP) {
                                " - WebP is usually a third smaller than JPEG at the same quality."
                            } else {
                                " - JPEG is the safer choice for very old clients."
                            },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(
                        value = s.deviceCompressionQuality.toFloat(),
                        onValueChange = { vm.setDeviceCompressionQuality(it.roundToInt()) },
                        valueRange = 30f..100f,
                        steps = 13,
                        enabled = s.deviceCompression,
                    )
                    Text(
                        "Animated GIFs and WebP are never re-encoded - it would keep only the " +
                            "first frame.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            HorizontalDivider()
            Text("Upload defaults", style = MaterialTheme.typography.titleMedium)
            Text(
                "Every upload starts from these - the picker, the share sheet and text uploads alike.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FocusTarget("skip_sheet", focus) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = s.skipUploadSheet, onCheckedChange = vm::setSkipUploadSheet)
                Column(Modifier.padding(start = 8.dp)) {
                    Text("Upload immediately")
                    Text(
                        "Skip the options sheet and always use these settings.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            }
            UploadOptionsForm(
                options = options,
                folders = folders,
                onChange = {
                    draft = it
                    vm.setDefaults(it)
                },
                // A fixed filename makes no sense as a default for every upload; it is offered
                // per-upload in the sheet instead.
                showFilenameField = false,
                focus = focus,
            )
            OutlinedButton(
                onClick = {
                    draft = UploadOptions.DEFAULT
                    vm.clearDefaults()
                },
            ) { Text("Clear upload defaults") }

        }
    }
}

/** Deep-links to this app's notification settings, where the three channels can be tuned. */
private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = checked, onCheckedChange = onChange)
        Text(label, Modifier.padding(start = 8.dp))
    }
}

/**
 * Numeric setting backed by local text state. Editing must be allowed to pass through invalid
 * intermediates (including empty) without the field snapping back to the persisted value.
 */
@Composable
private fun NumberSetting(
    initial: Int,
    label: String,
    onCommit: (Int) -> Unit,
    supporting: String? = null,
    enabled: Boolean = true,
) {
    var text by remember { mutableStateOf(initial.toString()) }

    // The settings flow publishes AppSettings() defaults first and the stored value a moment
    // later, so without this the field is seeded from the default and never catches up - it showed
    // 10 while 42 was on disk, which looks exactly like "saving does not work". Guarded on
    // inequality so it only corrects the field when the value really changed underneath, never
    // while the same number is being typed.
    LaunchedEffect(initial) {
        if (text.toIntOrNull() != initial) text = initial.toString()
    }

    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val digits = raw.filter(Char::isDigit).take(6)
            text = digits
            digits.toIntOrNull()?.let(onCommit)
        },
        label = { Text(label) },
        supportingText = supporting?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )
}
