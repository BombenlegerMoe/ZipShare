package dev.zipshare.ui.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zipshare.ui.shell.EmptyOrError
import dev.zipshare.ui.shell.PullRefresh
import dev.zipshare.ui.shell.ShellTopBar
import java.time.Instant
import java.time.temporal.ChronoUnit

private val EXPIRY_PRESETS = listOf(
    "never" to null,
    "1 day" to 1L,
    "7 days" to 7L,
    "30 days" to 30L,
)

@Composable
fun InvitesScreen(onMenu: () -> Unit, vm: AdminViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    var creating by remember { mutableStateOf(false) }
    var expiryDays by remember { mutableStateOf<Long?>(null) }
    var maxUses by remember { mutableStateOf("") }

    val base = state.active?.baseUrl.orEmpty().trimEnd('/')

    LaunchedEffect(state.active?.id) { if (state.active != null) vm.loadInvites() }
    LaunchedEffect(state.error) { state.error?.let { snackbar.showSnackbar(it); vm.clearError() } }
    LaunchedEffect(state.notice) { state.notice?.let { snackbar.showSnackbar(it); vm.clearNotice() } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            ShellTopBar(
                title = "Invites",
                profiles = state.profiles,
                activeLabel = state.active?.label,
                onMenu = onMenu,
                onSelectProfile = vm::selectProfile,
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { creating = true }) {
                Icon(Icons.Filled.Add, "Create invite")
            }
        },
    ) { padding ->
        PullRefresh(
            refreshing = state.loading,
            onRefresh = vm::loadInvites,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
        Column(Modifier.fillMaxSize()) {
            if (state.invites.isEmpty() && !state.loading) {
                EmptyOrError("No invites. Create one with the + button.")
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
                    items(state.invites, key = { it.id }) { invite ->
                        ListItem(
                            headlineContent = { Text(invite.code) },
                            supportingContent = {
                                Text(
                                    buildString {
                                        append("${invite.uses} use(s)")
                                        invite.maxUses?.let { append(" / max $it") }
                                        invite.expiresAt?.let { append(" - expires $it") }
                                            ?: append(" - never expires")
                                        invite.inviter?.username?.let { append(" - by $it") }
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                            leadingContent = { Icon(Icons.Filled.Tag, null) },
                            trailingContent = {
                                IconButton(onClick = { vm.deleteInvite(invite) }) {
                                    Icon(Icons.Filled.Delete, "Delete")
                                }
                            },
                            // Tapping copies the join link, which is the point of an invite.
                            modifier = Modifier.clickable {
                                clipboard.setText(AnnotatedString("$base/invite/${invite.code}"))
                            },
                        )
                    }
                }
            }
        }
        }
    }

    if (creating) {
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("Create invite") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Expires", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EXPIRY_PRESETS.forEach { (label, days) ->
                            FilterChip(
                                selected = expiryDays == days,
                                onClick = { expiryDays = days },
                                label = { Text(label) },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = maxUses,
                        onValueChange = { maxUses = it.filter(Char::isDigit) },
                        label = { Text("Max uses (blank = unlimited)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val expiresAt = expiryDays?.let {
                            Instant.now().plus(it, ChronoUnit.DAYS).toString()
                        }
                        vm.createInvite(expiresAt, maxUses.toIntOrNull()?.takeIf { it >= 1 })
                        creating = false
                        maxUses = ""
                        expiryDays = null
                    },
                ) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancel") } },
        )
    }
}
