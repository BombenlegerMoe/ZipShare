package dev.zipshare.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zipshare.ui.shell.PullRefresh
import dev.zipshare.ui.shell.ShellTopBar

private data class PendingAction(
    val title: String,
    val body: String,
    val confirmLabel: String,
    val destructive: Boolean,
    val run: () -> Unit,
)

@Composable
fun ServerActionsScreen(onMenu: () -> Unit, vm: AdminViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var pending by remember { mutableStateOf<PendingAction?>(null) }
    var forceDelete by remember { mutableStateOf(false) }
    var forceUpdate by remember { mutableStateOf(false) }
    var rerunThumbs by remember { mutableStateOf(false) }

    LaunchedEffect(state.active?.id) { if (state.active != null) vm.loadZeroFiles() }
    LaunchedEffect(state.error) { state.error?.let { snackbar.showSnackbar(it); vm.clearError() } }
    LaunchedEffect(state.notice) { state.notice?.let { snackbar.showSnackbar(it); vm.clearNotice() } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            ShellTopBar(
                title = "Server actions",
                profiles = state.profiles,
                activeLabel = state.active?.label,
                onMenu = onMenu,
                onSelectProfile = vm::selectProfile,
            )
        },
    ) { padding ->
        PullRefresh(
            refreshing = state.loading,
            onRefresh = vm::loadZeroFiles,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
        Column(
            Modifier.fillMaxSize().padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ActionCard(
                title = "Clear temporary files",
                description = "Deletes partial-upload leftovers from the server's temp directory.",
            ) {
                Button(
                    onClick = {
                        pending = PendingAction(
                            "Clear temporary files?",
                            "This permanently deletes the server's temporary upload files. " +
                                "Any partial upload still in progress will be lost.",
                            "Clear",
                            destructive = true,
                        ) { vm.clearTemp() }
                    },
                ) { Text("Clear temp") }
            }

            ActionCard(
                title = "Clear zero-byte files",
                description = if (state.zeroFiles.isEmpty()) {
                    "No zero-byte files found."
                } else {
                    "${state.zeroFiles.size} zero-byte file(s) found: " +
                        state.zeroFiles.take(5).joinToString { it.name } +
                        if (state.zeroFiles.size > 5) ", ..." else ""
                },
            ) {
                Button(
                    enabled = state.zeroFiles.isNotEmpty(),
                    onClick = {
                        pending = PendingAction(
                            "Delete ${state.zeroFiles.size} zero-byte file(s)?",
                            "These database records and their files are removed permanently.",
                            "Delete",
                            destructive = true,
                        ) { vm.clearZeros() }
                    },
                ) { Text("Clear zeros") }
            }

            ActionCard(
                title = "Re-query file sizes",
                description = "Re-scans stored files and refreshes their recorded sizes.",
            ) {
                Column {
                    ToggleRow("Force update every record", forceUpdate) { forceUpdate = it }
                    ToggleRow("Delete records whose file is missing", forceDelete) { forceDelete = it }
                    Button(
                        modifier = Modifier.padding(top = 6.dp),
                        onClick = {
                            pending = PendingAction(
                                "Re-query file sizes?",
                                buildString {
                                    append("Re-scans every stored file.")
                                    if (forceDelete) {
                                        append(
                                            " \"Delete missing\" is on, so records whose file is " +
                                                "gone from disk will be permanently removed.",
                                        )
                                    }
                                },
                                "Run",
                                destructive = forceDelete,
                            ) { vm.requerySize(forceDelete, forceUpdate) }
                        },
                    ) { Text("Re-query sizes") }
                }
            }

            ActionCard(
                title = "Generate thumbnails",
                description = "Builds video thumbnails in the background.",
            ) {
                Column {
                    ToggleRow("Re-run for files that already have one", rerunThumbs) { rerunThumbs = it }
                    Button(
                        modifier = Modifier.padding(top = 6.dp),
                        onClick = {
                            pending = PendingAction(
                                "Generate thumbnails?",
                                "This queues background work on the server and can be CPU heavy.",
                                "Start",
                                destructive = false,
                            ) { vm.generateThumbnails(rerunThumbs) }
                        },
                    ) { Text("Generate") }
                }
            }
        }
        }
    }

    // Every action here changes server-side state, so none of them fire without confirmation.
    pending?.let { action ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(action.title) },
            text = { Text(action.body) },
            confirmButton = {
                TextButton(
                    onClick = {
                        action.run()
                        pending = null
                    },
                ) {
                    Text(
                        action.confirmLabel,
                        color = if (action.destructive) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ActionCard(title: String, description: String, control: @Composable () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 6.dp),
            )
            control()
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = checked, onCheckedChange = onChange)
        Text(label, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodySmall)
    }
}
