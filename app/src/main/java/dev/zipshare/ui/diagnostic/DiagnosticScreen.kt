package dev.zipshare.ui.diagnostic

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zipshare.BuildConfig
import dev.zipshare.data.net.VersionResponse
import dev.zipshare.ui.settings.SettingsViewModel
import dev.zipshare.ui.FocusTarget
import dev.zipshare.ui.shareFile

/**
 * Everything you reach for when something is wrong or you are filing a bug: the on-device logs,
 * the upload history, a settings backup, and which versions are actually running.
 *
 * Split out of Settings because none of it is a setting - Settings is for changing how the app
 * behaves, this page is for looking at what it did.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticScreen(
    onMenu: () -> Unit,
    /** Row id from search: scroll to it and flash it on arrival. */
    focus: String? = null,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val message by vm.message.collectAsStateWithLifecycle()
    val version by vm.serverVersion.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }

    val pickSettings = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(vm::importSettings) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Diagnostic") },
                navigationIcon = {
                    IconButton(onClick = onMenu) { Icon(Icons.Filled.Menu, "Menu") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            FocusTarget("history", focus) { Text("History", style = MaterialTheme.typography.titleMedium) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    // Writing to cache and stopping there left the export unreachable; hand it
                    // to a share chooser so it can actually go somewhere.
                    onClick = { vm.exportHistory { file -> shareFile(context, file, "application/json") } },
                ) { Text("Export JSON") }
                OutlinedButton(onClick = vm::clearHistory) { Text("Clear history") }
            }

            HorizontalDivider()
            FocusTarget("logs", focus) { Text("Logs", style = MaterialTheme.typography.titleMedium) }
            Text(
                "The app keeps an activity log (uploads, errors, server switches - never tokens " +
                    "or passwords). It is stored encrypted and can only be read by exporting it " +
                    "here, which decrypts it to a plain text file.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { vm.exportLog { file -> shareFile(context, file, "text/plain") } },
                ) { Text("Export log") }
                OutlinedButton(onClick = vm::clearLog) { Text("Clear log") }
            }
            Text(
                "The separate login log records only lock/unlock events and can also be " +
                    "exported from the lock screen while the app is locked.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { vm.exportAuthLog { file -> shareFile(context, file, "text/plain") } },
                ) { Text("Export login log") }
                OutlinedButton(onClick = vm::clearAuthLog) { Text("Clear login log") }
            }

            HorizontalDivider()
            FocusTarget("backup", focus) { Text("Backup and Import", style = MaterialTheme.typography.titleMedium) }
            Text(
                "Export writes every setting from the Settings page - security, dashboard, " +
                    "appearance, uploads, notifications and upload defaults - to a JSON file. " +
                    "Servers, tokens and certificate pins are deliberately left out, so the file " +
                    "is safe to keep anywhere. Import reads one back and replaces your current " +
                    "settings with it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { vm.exportSettings { file -> shareFile(context, file, "application/json") } },
                ) { Text("Export settings") }
                OutlinedButton(
                    // "*/*" rather than a JSON filter: the mime a provider reports for a .json
                    // file varies (text/plain, octet-stream), and a file the user cannot even
                    // select is worse than one the parser rejects with a clear message.
                    onClick = { pickSettings.launch(arrayOf("*/*")) },
                ) { Text("Import settings") }
            }

            HorizontalDivider()
            FocusTarget("version", focus) { ServerVersionSection(version) }

            Spacer(Modifier.height(8.dp))
            Text(
                "ZipShare ${BuildConfig.VERSION_NAME}" + if (BuildConfig.DEBUG) " - debug" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The server's own version, mirroring the Version panel on Zipline's web dashboard.
 *
 * `data` is only present when the instance could reach GitHub to compare releases, so the
 * up-to-date line is shown only when there is something real to say - an air-gapped server should
 * not be told it is out of date.
 */
@Composable
private fun ServerVersionSection(version: VersionResponse?) {
    Text("Server", style = MaterialTheme.typography.titleMedium)

    if (version == null) {
        Text(
            "Could not read the server version.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    val tag = version.data?.version?.tag ?: version.details?.version
    val sha = (version.details?.sha ?: version.data?.version?.sha)?.take(7)

    version.data?.let { d ->
        Text(
            if (d.isLatest) {
                "Running the latest version of Zipline."
            } else {
                "An update is available" + (d.latest?.tag?.let { ": $it" } ?: ".")
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (d.isLatest) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.primary
            },
        )
    }

    VersionRow("Version", tag?.let { if (it.startsWith("v")) it else "v$it" } ?: "unknown")
    sha?.let { VersionRow("Commit", it) }
    version.data?.let { VersionRow("Upstream?", if (it.isUpstream) "Yes" else "No") }
}

@Composable
private fun VersionRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
