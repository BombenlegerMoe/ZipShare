package dev.zipshare.ui.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Public
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zipshare.data.net.shortLink
import dev.zipshare.ui.shell.EmptyOrError
import dev.zipshare.ui.shell.PullRefresh
import dev.zipshare.ui.shell.ShellTopBar

@Composable
fun FoldersScreen(
    onMenu: () -> Unit,
    onOpenFolder: (id: String, name: String) -> Unit,
    vm: BrowseViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<dev.zipshare.data.net.ZFolder?>(null) }

    LaunchedEffect(state.active?.id) { if (state.active != null) vm.loadFolders() }
    LaunchedEffect(state.error) { state.error?.let { snackbar.showSnackbar(it); vm.clearError() } }

    if (creating) {
        CreateFolderDialog(
            existing = state.folders,
            onDismiss = { creating = false },
            onCreate = { name, isPublic, parentId -> vm.createFolder(name, isPublic, parentId) },
        )
    }
    editing?.let { folder ->
        EditFolderDialog(
            folder = folder,
            onDismiss = { editing = null },
            onSave = { name, isPublic, allowUploads ->
                vm.patchFolder(folder.id, name, isPublic, allowUploads)
            },
            onDelete = { keepFiles -> vm.deleteFolder(folder.id, keepFiles) },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(onClick = { creating = true }) { Icon(Icons.Filled.Add, "New folder") }
        },
        topBar = {
            ShellTopBar(
                title = "Folders",
                profiles = state.profiles,
                activeLabel = state.active?.label,
                onMenu = onMenu,
                onSelectProfile = vm::selectProfile,
            )
        },
    ) { padding ->
        PullRefresh(
            refreshing = state.loading,
            onRefresh = vm::loadFolders,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
        Column(Modifier.fillMaxSize()) {
            if (state.folders.isEmpty() && !state.loading) {
                EmptyOrError("No folders on this server.")
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
                    items(state.folders, key = { it.id }) { folder ->
                        ListItem(
                            headlineContent = { Text(folder.name) },
                            supportingContent = {
                                Text(
                                    buildString {
                                        if (folder.isPublic) append("public") else append("private")
                                        if (folder.allowUploads) append(" - accepts uploads")
                                        folder.parentId?.let { pid ->
                                            state.folders.firstOrNull { it.id == pid }
                                                ?.let { append(" - in ${it.name}") }
                                        }
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                            leadingContent = { Icon(Icons.Filled.Folder, null) },
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (folder.isPublic) {
                                        Icon(Icons.Filled.Public, "Public", Modifier.size(18.dp))
                                    }
                                    IconButton(onClick = { editing = folder }) {
                                        Icon(Icons.Filled.Edit, "Edit folder", Modifier.size(18.dp))
                                    }
                                }
                            },
                            modifier = Modifier.clickable { onOpenFolder(folder.id, folder.name) },
                        )
                    }
                }
            }
        }
        }
    }
}

@Composable
fun UrlsScreen(onMenu: () -> Unit, vm: BrowseViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    val base = state.active?.baseUrl.orEmpty().trimEnd('/')
    var creating by remember { mutableStateOf(false) }

    LaunchedEffect(state.active?.id) { if (state.active != null) vm.loadUrls() }
    LaunchedEffect(state.error) { state.error?.let { snackbar.showSnackbar(it); vm.clearError() } }

    if (creating) {
        CreateUrlDialog(
            onDismiss = { creating = false },
            onCreate = { destination, vanity -> vm.createUrl(destination, vanity) },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(onClick = { creating = true }) { Icon(Icons.Filled.Add, "New URL") }
        },
        topBar = {
            ShellTopBar(
                title = "URLs",
                profiles = state.profiles,
                activeLabel = state.active?.label,
                onMenu = onMenu,
                onSelectProfile = vm::selectProfile,
            )
        },
    ) { padding ->
        PullRefresh(
            refreshing = state.loading,
            onRefresh = vm::loadUrls,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
        Column(Modifier.fillMaxSize()) {
            if (state.urls.isEmpty() && !state.loading) {
                EmptyOrError("No shortened links on this server.")
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
                    items(state.urls, key = { it.id }) { url ->
                        val short = url.shortLink(base)
                        ListItem(
                            headlineContent = {
                                Text(url.vanity ?: url.code, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            supportingContent = {
                                Column {
                                    Text(
                                        url.destination,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        buildString {
                                            append("${url.views} views")
                                            url.maxViews?.let { append(" / max $it") }
                                            if (!url.enabled) append(" - disabled")
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            },
                            leadingContent = { Icon(Icons.Filled.Link, null) },
                            trailingContent = {
                                IconButton(onClick = { vm.deleteUrl(url) }) {
                                    Icon(Icons.Filled.Delete, "Delete")
                                }
                            },
                            modifier = Modifier.clickable {
                                clipboard.setText(AnnotatedString(short))
                            },
                        )
                    }
                }
            }
        }
        }
    }
}

@Composable
fun UsersScreen(onMenu: () -> Unit, vm: BrowseViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<dev.zipshare.data.net.ZLimitedUser?>(null) }

    LaunchedEffect(state.active?.id) {
        if (state.active != null) {
            vm.loadUsers()
            vm.loadMe()
        }
    }
    LaunchedEffect(state.error) { state.error?.let { snackbar.showSnackbar(it); vm.clearError() } }

    if (creating) {
        CreateUserDialog(
            onDismiss = { creating = false },
            onCreate = { username, password, role -> vm.createUser(username, password, role) },
        )
    }
    editing?.let { user ->
        EditUserDialog(
            user = user,
            isSelf = user.id == state.me?.id,
            onDismiss = { editing = null },
            onSave = { username, password, role, quota ->
                vm.patchUser(user.id, username, password, role, quota)
            },
            onDelete = { alsoContent -> vm.deleteUser(user.id, alsoContent) },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(onClick = { creating = true }) { Icon(Icons.Filled.Add, "New user") }
        },
        topBar = {
            ShellTopBar(
                title = "Users",
                profiles = state.profiles,
                activeLabel = state.active?.label,
                onMenu = onMenu,
                onSelectProfile = vm::selectProfile,
            )
        },
    ) { padding ->
        PullRefresh(
            refreshing = state.loading,
            onRefresh = vm::loadUsers,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
        Column(Modifier.fillMaxSize()) {
            if (state.users.isEmpty() && !state.loading) {
                // Distinguish "the server answered with an empty list" from "the request failed".
                // A non-admin token gets E3000, and the snackbar carries the server's own words.
                EmptyOrError(
                    if (state.usersLoaded) {
                        "No other users on this server."
                    } else {
                        "Could not load users. This page needs an administrator token."
                    },
                )
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
                    items(state.users, key = { it.id }) { user ->
                        ListItem(
                            headlineContent = { Text(user.username) },
                            supportingContent = {
                                Text(
                                    buildString {
                                        append(user.role)
                                        user.quota?.maxBytes?.let { append(" - quota $it") }
                                        user.quota?.maxFiles?.let { append(" - $it files") }
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                            leadingContent = { Icon(Icons.Filled.Group, null) },
                            trailingContent = {
                                IconButton(onClick = { editing = user }) {
                                    Icon(Icons.Filled.Edit, "Edit user", Modifier.size(18.dp))
                                }
                            },
                        )
                    }
                }
            }
        }
        }
    }
}

/** Rename, toggle visibility and uploads, or delete - the same options the web dashboard has. */
/**
 * The AlertDialog skeleton every dialog here shares: a title, a body, a confirm button whose label
 * and enablement vary, and a Cancel that only dismisses. The body stays a plain slot so each
 * dialog keeps its own Column arrangement - imposing one here would reflow half of them.
 */
@Composable
private fun FormDialog(
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmEnabled: Boolean = true,
    body: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = body,
        confirmButton = {
            TextButton(enabled = confirmEnabled, onClick = onConfirm) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun EditFolderDialog(
    folder: dev.zipshare.data.net.ZFolder,
    onDismiss: () -> Unit,
    onSave: (name: String?, isPublic: Boolean?, allowUploads: Boolean?) -> Unit,
    onDelete: (keepFiles: Boolean) -> Unit,
) {
    var name by remember { mutableStateOf(folder.name) }
    var isPublic by remember { mutableStateOf(folder.isPublic) }
    var allowUploads by remember { mutableStateOf(folder.allowUploads) }
    var confirmDelete by remember { mutableStateOf(false) }

    if (confirmDelete) {
        var keepFiles by remember { mutableStateOf(true) }
        FormDialog(
            title = "Delete \"${folder.name}\"?",
            confirmLabel = "Delete",
            onConfirm = { confirmDelete = false; onDelete(keepFiles); onDismiss() },
            onDismiss = { confirmDelete = false },
        ) {
            Column {
                Text("What should happen to the files inside it?")
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = keepFiles, onCheckedChange = { keepFiles = it })
                    Text(
                        if (keepFiles) {
                            "Keep them - they move out of the folder"
                        } else {
                            "Delete them along with the folder"
                        },
                        Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }

    FormDialog(
        title = "Edit folder",
        confirmLabel = "Save",
        confirmEnabled = name.isNotBlank(),
        onConfirm = {
            // Only send what actually changed.
            onSave(
                name.trim().takeIf { it != folder.name },
                isPublic.takeIf { it != folder.isPublic },
                allowUploads.takeIf { it != folder.allowUploads },
            )
            onDismiss()
        },
        onDismiss = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = isPublic, onCheckedChange = { isPublic = it })
                Text("Public", Modifier.padding(start = 8.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = allowUploads, onCheckedChange = { allowUploads = it })
                Text("Allow uploads from others", Modifier.padding(start = 8.dp))
            }
            TextButton(onClick = { confirmDelete = true }) {
                Text("Delete folder", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** Rename, set a new password, change role and quota, or delete the account. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditUserDialog(
    user: dev.zipshare.data.net.ZLimitedUser,
    isSelf: Boolean,
    onDismiss: () -> Unit,
    onSave: (
        username: String?,
        password: String?,
        role: String?,
        quota: dev.zipshare.data.net.QuotaBody?,
    ) -> Unit,
    onDelete: (alsoContent: Boolean) -> Unit,
) {
    var username by remember { mutableStateOf(user.username) }
    var password by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(user.role) }
    var maxFiles by remember { mutableStateOf(user.quota?.maxFiles?.toString().orEmpty()) }
    var maxBytes by remember { mutableStateOf(user.quota?.maxBytes.orEmpty()) }
    var maxUrls by remember { mutableStateOf(user.quota?.maxUrls?.toString().orEmpty()) }
    var confirmDelete by remember { mutableStateOf(false) }

    if (confirmDelete) {
        var alsoContent by remember { mutableStateOf(false) }
        FormDialog(
            title = "Delete ${user.username}?",
            confirmLabel = "Delete",
            onConfirm = { confirmDelete = false; onDelete(alsoContent); onDismiss() },
            onDismiss = { confirmDelete = false },
        ) {
            Column {
                Text("The account is removed from the server.")
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = alsoContent, onCheckedChange = { alsoContent = it })
                    Text(
                        "Also delete their files and links",
                        Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }

    FormDialog(
        title = "Edit ${user.username}",
        confirmLabel = "Save",
        confirmEnabled = username.isNotBlank(),
        onConfirm = {
            val quota = if (maxBytes.isNotBlank() || maxFiles.isNotBlank() || maxUrls.isNotBlank()) {
                dev.zipshare.data.net.QuotaBody(
                    filesType = if (maxBytes.isNotBlank()) "BY_BYTES" else "BY_FILES",
                    maxBytes = maxBytes.trim().takeIf { it.isNotEmpty() },
                    maxFiles = maxFiles.toIntOrNull(),
                    maxUrls = maxUrls.toIntOrNull(),
                )
            } else {
                null
            }
            onSave(
                username.trim().takeIf { it != user.username },
                password.takeIf { it.isNotBlank() },
                role.takeIf { it != user.role },
                quota,
            )
            onDismiss()
        },
        onDismiss = onDismiss,
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("New password") },
                    supportingText = { Text("Leave empty to keep the current one.") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
                Text("Role", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("USER", "ADMIN", "SUPERADMIN").forEach { r ->
                        FilterChip(
                            selected = role == r,
                            onClick = { role = r },
                            label = { Text(r) },
                        )
                    }
                }
                Text("Quota", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(
                    value = maxBytes,
                    onValueChange = { maxBytes = it },
                    label = { Text("Max storage") },
                    supportingText = { Text("For example 10gb. Empty for unlimited.") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = maxFiles,
                    onValueChange = { maxFiles = it.filter(Char::isDigit) },
                    label = { Text("Max files") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = maxUrls,
                    onValueChange = { maxUrls = it.filter(Char::isDigit) },
                    label = { Text("Max links") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                if (isSelf) {
                    Text(
                        "This is the account you are signed in as - the server refuses to delete it.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    TextButton(onClick = { confirmDelete = true }) {
                        Text("Delete user", color = MaterialTheme.colorScheme.error)
                    }
                }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreateFolderDialog(
    existing: List<dev.zipshare.data.net.ZFolder>,
    onDismiss: () -> Unit,
    onCreate: (name: String, isPublic: Boolean, parentId: String?) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var isPublic by remember { mutableStateOf(false) }
    var parentId by remember { mutableStateOf<String?>(null) }
    FormDialog(
        title = "New folder",
        confirmLabel = "Create",
        confirmEnabled = name.isNotBlank(),
        onConfirm = { onCreate(name.trim(), isPublic, parentId); onDismiss() },
        onDismiss = onDismiss,
    ) {
        Column {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
            )
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = isPublic, onCheckedChange = { isPublic = it })
                Text("Public", Modifier.padding(start = 8.dp))
            }
            if (existing.isNotEmpty()) {
                Text(
                    "Inside (optional)",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 8.dp),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    existing.forEach { f ->
                        FilterChip(
                            selected = parentId == f.id,
                            onClick = { parentId = if (parentId == f.id) null else f.id },
                            label = { Text(f.name) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateUrlDialog(onDismiss: () -> Unit, onCreate: (String, String?) -> Unit) {
    var destination by remember { mutableStateOf("") }
    var vanity by remember { mutableStateOf("") }
    FormDialog(
        title = "Shorten a URL",
        confirmLabel = "Create",
        confirmEnabled = destination.startsWith("http"),
        onConfirm = { onCreate(destination.trim(), vanity.trim()); onDismiss() },
        onDismiss = onDismiss,
    ) {
        Column {
            OutlinedTextField(
                value = destination,
                onValueChange = { destination = it },
                label = { Text("Destination (https://...)") },
                singleLine = true,
            )
            OutlinedTextField(
                value = vanity,
                onValueChange = { vanity = it },
                label = { Text("Vanity code (optional)") },
                singleLine = true,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun CreateUserDialog(onDismiss: () -> Unit, onCreate: (String, String, String) -> Unit) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("USER") }
    FormDialog(
        title = "New user",
        confirmLabel = "Create",
        confirmEnabled = username.isNotBlank() && password.isNotBlank(),
        onConfirm = { onCreate(username.trim(), password, role); onDismiss() },
        onDismiss = onDismiss,
    ) {
        Column {
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Username") },
                singleLine = true,
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(
                Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("USER", "ADMIN").forEach { r ->
                    FilterChip(
                        selected = role == r,
                        onClick = { role = r },
                        label = { Text(r) },
                    )
                }
            }
        }
    }
}
