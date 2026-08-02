package dev.zipshare.ui.servers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersScreen(
    onMenu: () -> Unit,
    onEdit: (String?) -> Unit,
    vm: ServersViewModel = hiltViewModel(),
) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val activeId by vm.activeId.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Servers") },
                navigationIcon = {
                    IconButton(onClick = onMenu) { Icon(Icons.Filled.Menu, "Menu") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onEdit(null) }) {
                Icon(Icons.Filled.Add, "Add server")
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            items(profiles, key = { it.id }) { profile ->
                Row(
                    Modifier.fillMaxWidth().clickable { onEdit(profile.id) }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { vm.setActive(profile.id) }) {
                        if (profile.id == activeId) Icon(Icons.Filled.Check, "Active")
                    }
                    Column(Modifier.weight(1f)) {
                        Text(profile.label, style = MaterialTheme.typography.titleMedium)
                        Text(profile.baseUrl, style = MaterialTheme.typography.bodySmall)
                        val flags = buildList {
                            if (profile.pinnedSpkiSha256 != null) add("pinned")
                            if (profile.allowCleartext) add("cleartext")
                            if (!profile.authenticated) add("token invalid")
                        }
                        if (flags.isNotEmpty()) {
                            Text(
                                flags.joinToString(" - "),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (profile.authenticated) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            )
                        }
                    }
                    IconButton(onClick = { vm.delete(profile.id) }) {
                        Icon(Icons.Filled.Delete, "Delete")
                    }
                }
            }
        }
    }
}
