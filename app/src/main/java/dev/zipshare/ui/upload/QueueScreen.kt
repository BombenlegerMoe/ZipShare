package dev.zipshare.ui.upload

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import dev.zipshare.ui.shell.EmptyOrError
import dev.zipshare.ui.shell.ShellTopBar

/**
 * What is waiting to upload, what is uploading, and what failed.
 *
 * There is no pause button. WorkManager can only cancel and re-enqueue, and a re-enqueue restarts
 * the file from zero - so a "pause" would be a resume that silently re-uploads everything. There is
 * no retry button either: a run that fails for good gives up its staged copy and its password on
 * the way out (deliberately, so neither lingers on disk), which leaves nothing to retry from.
 *
 * There is no "Clear failed" button, because failed rows are not something to tidy up: they are
 * read here and then dropped when the screen is left. See [QueueViewModel.onCleared].
 */
@Composable
fun QueueScreen(onMenu: () -> Unit, vm: QueueViewModel = hiltViewModel()) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val active by vm.active.collectAsStateWithLifecycle()
    val anyPending = rows.any { !it.failed }

    Scaffold(
        topBar = {
            ShellTopBar(
                title = "Upload queue",
                profiles = profiles,
                activeLabel = active?.label,
                onMenu = onMenu,
                onSelectProfile = vm::selectProfile,
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (rows.isEmpty()) {
                EmptyOrError("Nothing in the queue.")
                return@Column
            }

            if (anyPending) {
                TextButton(
                    onClick = vm::cancelAll,
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) { Text("Cancel all") }
                HorizontalDivider()
            }

            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(rows, key = { it.id }) { row ->
                    ListItem(
                        headlineContent = {
                            Text(row.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = {
                            Column {
                                Text(
                                    row.error ?: row.state.label(row.percent),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (row.failed) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                                if (row.running) {
                                    LinearProgressIndicator(
                                        progress = { row.percent / 100f },
                                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                    )
                                }
                            }
                        },
                        trailingContent = {
                            if (!row.failed) {
                                IconButton(onClick = { vm.cancel(row.id) }) {
                                    Icon(Icons.Filled.Close, "Cancel ${row.name}")
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

private fun WorkInfo.State.label(percent: Int): String = when (this) {
    WorkInfo.State.RUNNING -> "Uploading - $percent%"
    // Either the network constraint is unmet or the executor is busy; "waiting" covers both without
    // claiming a reason that may be wrong.
    WorkInfo.State.ENQUEUED -> "Waiting to start"
    // A chain uploads one file at a time, so everything behind the current one is BLOCKED.
    WorkInfo.State.BLOCKED -> "Queued"
    WorkInfo.State.FAILED -> "Failed"
    else -> name.lowercase()
}
