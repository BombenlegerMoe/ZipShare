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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zipshare.ui.FocusTarget
import dev.zipshare.ui.shell.EmptyOrError
import dev.zipshare.ui.shell.PullRefresh
import dev.zipshare.ui.shell.ShellTopBar

/**
 * Generic editor over whatever /api/server/settings returns. Zipline has ~100 settings keys and
 * they change between versions, so the form is generated from the response rather than hand-written;
 * only the fields the user actually edits get PATCHed back.
 */
@Composable
fun ServerSettingsScreen(
    onMenu: () -> Unit,
    /** A setting key search wants opened; see the effect below. */
    focus: String? = null,
    vm: AdminViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmSave by remember { mutableStateOf(false) }

    // Collapsed by default. Zipline returns ~100 keys across a dozen groups, which as one flat
    // list is a wall of fields you have to scroll past to reach anything.
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val listState = rememberLazyListState()

    /**
     * Search hands over the key of a row that lives inside a collapsed group, so the group has to
     * be opened before there is a row to scroll to at all - a plain bringIntoView finds nothing,
     * because a closed group never composes its children.
     *
     * Everything else closes on the way in, which is what keeps the arithmetic honest: with one
     * group open, header i is item i, so the target group's position in the list *is* its index.
     * Keyed on the settings count rather than the list, or editing a field would re-scroll on
     * every keystroke.
     */
    LaunchedEffect(focus, state.settings.size) {
        val target = focus ?: return@LaunchedEffect
        val group = state.settings.firstOrNull { it.key == target }?.group ?: return@LaunchedEffect
        expanded.clear()
        expanded[group] = true
        listState.animateScrollToItem(state.settings.map { it.group }.distinct().indexOf(group))
    }

    LaunchedEffect(state.active?.id) { if (state.active != null) vm.loadServerSettings() }
    LaunchedEffect(state.error) { state.error?.let { snackbar.showSnackbar(it); vm.clearError() } }
    LaunchedEffect(state.notice) { state.notice?.let { snackbar.showSnackbar(it); vm.clearNotice() } }

    val dirty = state.settings.count { it.dirty }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            ShellTopBar(
                title = "Server settings",
                profiles = state.profiles,
                activeLabel = state.active?.label,
                onMenu = onMenu,
                onSelectProfile = vm::selectProfile,
            ) {
                if (dirty > 0) {
                    TextButton(onClick = vm::resetSettings) { Text("Reset") }
                }
            }
        },
        floatingActionButton = {
            if (dirty > 0) {
                ExtendedFloatingActionButton(
                    onClick = { confirmSave = true },
                    icon = { Icon(Icons.Filled.Save, null) },
                    text = { Text("Save $dirty") },
                )
            }
        },
    ) { padding ->
        PullRefresh(
            // Refetching would silently throw away unsaved edits, so a pull is ignored while the
            // form is dirty - "Reset" is the deliberate way to discard them.
            refreshing = state.loading,
            onRefresh = { if (dirty == 0) vm.loadServerSettings() },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
        Column(Modifier.fillMaxSize()) {
            if (state.saving) LinearProgressIndicator(Modifier.fillMaxWidth())

            if (state.settings.isEmpty() && !state.loading) {
                EmptyOrError("Could not read server settings. This needs a superadmin token.")
            } else {
                val grouped = state.settings.groupBy { it.group }

                LazyColumn(
                    Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(16.dp),
                ) {
                    grouped.forEach { (group, entries) ->
                        val open = expanded[group] == true
                        item(key = "hdr-$group") {
                            GroupHeader(
                                title = groupTitle(group),
                                count = entries.size,
                                dirty = entries.count { it.dirty },
                                expanded = open,
                                // Only this group needs the caveat. A blanket note would be
                                // wrong: limits like max file size do apply to this app.
                                note = if (group.equals("chunks", ignoreCase = true)) {
                                    "These control the web dashboard's uploader. ZipShare " +
                                        "uses its own values under Settings > Chunked upload."
                                } else {
                                    null
                                },
                                onToggle = { expanded[group] = !open },
                            )
                        }
                        if (open) {
                            items(entries, key = { it.key }) { entry ->
                                // spacing = 0: the row brings its own padding, and the wrapper's
                                // default would space a single child away from nothing.
                                FocusTarget(id = entry.key, focus = focus, spacing = 0.dp) {
                                    SettingRow(
                                        entry = entry,
                                        onChange = { vm.editSetting(entry.key, it) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        }
    }

    if (confirmSave) {
        AlertDialog(
            onDismissRequest = { confirmSave = false },
            title = { Text("Apply $dirty change(s)?") },
            text = {
                Column {
                    Text("These are written to the live server configuration:")
                    state.settings.filter { it.dirty }.take(10).forEach {
                        Text(
                            "- ${groupTitle(it.group)} / ${it.label}: " +
                                "${it.original.ifBlank { "(empty)" }} -> ${it.current.ifBlank { "(empty)" }}",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    if (dirty > 10) Text("...and ${dirty - 10} more.", style = MaterialTheme.typography.labelSmall)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.saveServerSettings()
                        confirmSave = false
                    },
                ) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { confirmSave = false }) { Text("Cancel") } },
        )
    }
}

/**
 * Collapsible section header for one settings group.
 *
 * The whole row is the toggle rather than just the chevron - a 24 dp icon is a poor target when
 * the row is already there.
 */
@Composable
private fun GroupHeader(
    title: String,
    count: Int,
    dirty: Int,
    expanded: Boolean,
    note: String?,
    onToggle: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(top = 14.dp, bottom = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse $title" else "Expand $title",
            )
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp).weight(1f),
            )
            // Without this an edit inside a collapsed group is invisible while the save button
            // still counts it, which reads as the app inventing changes.
            if (dirty > 0) {
                Badge { Text("$dirty") }
            } else {
                Text(
                    "$count",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (expanded && note != null) {
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, start = 32.dp),
            )
        }
        HorizontalDivider(Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun SettingRow(entry: SettingEntry, onChange: (String) -> Unit) {
    val label = buildString {
        append(entry.label)
        if (entry.tampered) append("  (set by env/config file)")
    }
    when (entry.kind) {
        SettingKind.BOOLEAN -> Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    entry.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (entry.dirty) FontWeight.Bold else FontWeight.Normal,
                )
                if (entry.tampered) {
                    Text(
                        "set by env/config file",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Switch(
                checked = entry.current.toBooleanStrictOrNull() ?: false,
                onCheckedChange = { onChange(it.toString()) },
            )
        }

        else -> OutlinedTextField(
            value = entry.current,
            onValueChange = onChange,
            label = { Text(label) },
            singleLine = true,
            isError = false,
            keyboardOptions = if (entry.kind == SettingKind.NUMBER) {
                KeyboardOptions(keyboardType = KeyboardType.Number)
            } else {
                KeyboardOptions.Default
            },
            supportingText = if (entry.dirty) {
                { Text("was: ${entry.original.ifBlank { "(empty)" }}") }
            } else {
                null
            },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
    }
}
