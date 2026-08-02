package dev.zipshare.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import dev.zipshare.data.net.FileSort
import dev.zipshare.data.model.formatLink
import dev.zipshare.data.net.ZFile
import dev.zipshare.data.net.hasVisualPreview
import dev.zipshare.data.net.isPlayable
import dev.zipshare.data.net.isTextLike
import dev.zipshare.data.net.previewUrl
import dev.zipshare.data.net.rawUrl
import dev.zipshare.data.net.shareUrl
import dev.zipshare.data.net.viewerUrl
import dev.zipshare.data.prefs.SettingsStore
import dev.zipshare.ui.home.humanSize
import dev.zipshare.ui.home.iconFor
import dev.zipshare.ui.shell.EmptyOrError
import dev.zipshare.ui.shell.LocalLinkFormat
import dev.zipshare.ui.shell.PullRefresh
import dev.zipshare.ui.shell.ShellTopBar
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FilesScreen(
    onMenu: () -> Unit,
    onOpenImage: (name: String, previewUrl: String, shareUrl: String, playbackUrl: String?) -> Unit,
    onOpenText: (name: String, rawUrl: String, mime: String) -> Unit = { _, _, _ -> },
    folderId: String? = null,
    folderName: String? = null,
    vm: BrowseViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val linkFormat = LocalLinkFormat.current
    val snackbar = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    val base = state.active?.baseUrl.orEmpty()

    var searchOpen by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var gridView by remember { mutableStateOf(true) }
    var detailFor by remember { mutableStateOf<ZFile?>(null) }
    var confirmBulkDelete by remember { mutableStateOf(false) }
    var moveTarget by remember { mutableStateOf(false) }

    LaunchedEffect(state.active?.id, folderId) {
        if (state.active != null) {
            vm.setFolderFilter(folderId, folderName)
            vm.loadTags()
            // Needed for the Move action and the detail sheet's folder name; without this the
            // folder list is empty here and Move stays permanently disabled.
            vm.loadFolders()
        }
    }
    LaunchedEffect(state.error) {
        state.error?.let { snackbar.showSnackbar(it); vm.clearError() }
    }
    // Debounce: search as you type without a request per keystroke.
    LaunchedEffect(state.search) {
        if (searchOpen) {
            delay(400)
            vm.applySearch()
        }
    }

    // The detail sheet holds a snapshot; keep it in step when the list row is replaced by an edit.
    val detail = detailFor?.let { snap -> state.files.firstOrNull { it.id == snap.id } ?: snap }
    if (detail != null) {
        FileDetailSheet(
            file = detail,
            baseUrl = base,
            allTags = state.tags,
            folders = state.folders,
            onDismiss = { detailFor = null },
            onToggleFavourite = { vm.toggleFavourite(detail) },
            onPatch = { name, maxViews, tags -> vm.patchFile(detail.id, name, maxViews, tags) },
            onMove = { folder -> vm.moveToFolder(listOf(detail.id), folder) },
            onCreateTag = vm::createTag,
            onEditTag = vm::editTag,
            onDeleteTag = vm::deleteTag,
            onSetPassword = { vm.setFilePassword(detail.id, it) },
            onDelete = {
                vm.deleteFile(detail)
                detailFor = null
            },
            onOpen = {
                if (detail.isTextLike()) {
                    onOpenText(detail.name, detail.rawUrl(base), detail.type)
                } else {
                    onOpenImage(
                        detail.name,
                        detail.viewerUrl(base),
                        detail.shareUrl(base),
                        detail.rawUrl(base).takeIf { detail.isPlayable() },
                    )
                }
            },
        )
    }

    if (confirmBulkDelete) {
        AlertDialog(
            onDismissRequest = { confirmBulkDelete = false },
            title = { Text("Delete ${state.selected.size} file(s)?") },
            text = { Text("They are removed from the server permanently. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { vm.bulkDelete(); confirmBulkDelete = false }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmBulkDelete = false }) { Text("Cancel") }
            },
        )
    }

    if (moveTarget) {
        FolderPickDialog(
            folders = state.folders,
            onDismiss = { moveTarget = false },
            onPick = { vm.moveToFolder(state.selected, it); moveTarget = false },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (state.selecting) {
                // Contextual bar replaces the normal one while selecting, like the web dashboard.
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                    navigationIcon = {
                        IconButton(onClick = vm::clearSelection) { Icon(Icons.Filled.Close, "Cancel") }
                    },
                    title = { Text("${state.selected.size} selected") },
                    actions = {
                        IconButton(onClick = vm::selectAllOnPage) {
                            Icon(Icons.Filled.SelectAll, "Select all on page")
                        }
                        IconButton(onClick = { vm.bulkFavourite(true) }) {
                            Icon(Icons.Filled.Star, "Favourite")
                        }
                        IconButton(
                            onClick = { moveTarget = true },
                            enabled = state.folders.isNotEmpty(),
                        ) { Icon(Icons.Filled.DriveFileMove, "Move to folder") }
                        IconButton(onClick = { confirmBulkDelete = true }) {
                            Icon(Icons.Filled.Delete, "Delete")
                        }
                    },
                )
            } else {
                ShellTopBar(
                    title = folderName?.let { "Files - $it" } ?: "Files",
                    profiles = state.profiles,
                    activeLabel = state.active?.label,
                    onMenu = onMenu,
                    onSelectProfile = vm::selectProfile,
                )
            }
        },
    ) { padding ->
        PullRefresh(
            refreshing = state.loading,
            onRefresh = { vm.loadFiles() },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
        Column(Modifier.fillMaxSize()) {

            // --- toolbar: search, sort, order, favourites, view mode ---
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = {
                    searchOpen = !searchOpen
                    if (!searchOpen && state.search.isNotEmpty()) {
                        vm.setSearch("")
                        vm.applySearch()
                    }
                }) { Icon(Icons.Filled.Search, "Search") }

                Box {
                    IconButton(onClick = { sortMenu = true }) { Icon(Icons.Filled.Sort, "Sort") }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        FileSort.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                trailingIcon = {
                                    if (state.sort == option) {
                                        Icon(Icons.Filled.Star, null, Modifier.size(14.dp))
                                    }
                                },
                                onClick = { vm.setSort(option); sortMenu = false },
                            )
                        }
                    }
                }
                IconButton(onClick = vm::toggleOrder) {
                    Icon(
                        if (state.ascending) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                        if (state.ascending) "Oldest first" else "Newest first",
                    )
                }
                IconButton(onClick = vm::toggleFavouritesOnly) {
                    Icon(
                        if (state.favouritesOnly) Icons.Filled.Star else Icons.Filled.StarBorder,
                        "Favourites only",
                        tint = if (state.favouritesOnly) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Box(Modifier.weight(1f))
                IconButton(onClick = { gridView = !gridView }) {
                    Icon(
                        if (gridView) Icons.AutoMirrored.Filled.ViewList else Icons.Filled.GridView,
                        if (gridView) "List view" else "Grid view",
                    )
                }
            }

            if (searchOpen) {
                Column(Modifier.padding(horizontal = 12.dp)) {
                    OutlinedTextField(
                        value = state.search,
                        onValueChange = vm::setSearch,
                        label = { Text("Search") },
                        singleLine = true,
                        trailingIcon = {
                            if (state.search.isNotEmpty()) {
                                IconButton(onClick = { vm.setSearch(""); vm.applySearch() }) {
                                    Icon(Icons.Filled.Close, "Clear search")
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            "name" to "Name",
                            "originalName" to "Original name",
                            "type" to "Type",
                            "tags" to "Tags",
                        ).forEach { (field, label) ->
                            FilterChip(
                                selected = state.searchField == field,
                                onClick = { vm.setSearchField(field) },
                                label = { Text(label) },
                            )
                        }
                    }
                }
            }

            if (state.files.isEmpty() && !state.loading) {
                EmptyOrError(
                    if (state.search.isNotBlank()) {
                        "Nothing matches \"${state.search}\"."
                    } else if (state.favouritesOnly) {
                        "No favourites yet."
                    } else {
                        "No files here yet."
                    },
                )
            } else {
                val open: (ZFile) -> Unit = { file ->
                    if (state.selecting) vm.toggleSelected(file.id) else detailFor = file
                }
                if (gridView) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 150.dp),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        items(state.files, key = { it.id }) { file ->
                            FileGridCard(
                                file = file,
                                base = base,
                                selected = file.id in state.selected,
                                onClick = { open(file) },
                                onLongClick = { vm.startSelecting(file.id) },
                                onCopy = { clipboard.setText(AnnotatedString(formatLink(file.name, file.shareUrl(base), linkFormat))) },
                            )
                        }
                    }
                } else {
                    LazyColumn(Modifier.weight(1f)) {
                        items(state.files, key = { it.id }) { file ->
                            FileRow(
                                file = file,
                                base = base,
                                selected = file.id in state.selected,
                                onClick = { open(file) },
                                onLongClick = { vm.startSelecting(file.id) },
                                onCopy = { clipboard.setText(AnnotatedString(formatLink(file.name, file.shareUrl(base), linkFormat))) },
                            )
                        }
                    }
                }

                // Pagination, matching the web dashboard's paged file list.
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = vm::prevPage, enabled = state.page > 1) { Text("Previous") }
                    Text(
                        "Page ${state.page} of ${state.totalPages}",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    TextButton(
                        onClick = vm::nextPage,
                        enabled = state.page < state.totalPages,
                    ) { Text("Next") }
                }
            }
        }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun FileGridCard(
    file: ZFile,
    base: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onCopy: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = if (selected) {
            Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
        } else {
            Modifier
        },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
            contentAlignment = Alignment.Center,
        ) {
            if (file.hasVisualPreview()) {
                AsyncImage(
                    model = file.previewUrl(base),
                    contentDescription = file.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(iconFor(file.type), null, Modifier.size(40.dp))
            }
            if (file.favorite) {
                Icon(
                    Icons.Filled.Star,
                    "Favourite",
                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    file.name,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${humanSize(file.size)} - ${file.views} views",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            IconButton(onClick = onCopy) {
                Icon(Icons.Filled.ContentCopy, "Copy link", Modifier.size(18.dp))
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    file: ZFile,
    base: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onCopy: () -> Unit,
) {
    ListItem(
        modifier = Modifier
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .then(
                if (selected) {
                    Modifier.background(MaterialTheme.colorScheme.primaryContainer)
                } else {
                    Modifier
                },
            ),
        leadingContent = {
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                if (file.hasVisualPreview()) {
                    AsyncImage(
                        model = file.previewUrl(base),
                        contentDescription = file.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(iconFor(file.type), null)
                }
            }
        },
        headlineContent = {
            Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text(
                "${humanSize(file.size)} - ${file.type} - ${file.views} views",
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (file.favorite) {
                    Icon(
                        Icons.Filled.Star,
                        "Favourite",
                        Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                IconButton(onClick = onCopy) {
                    Icon(Icons.Filled.ContentCopy, "Copy link", Modifier.size(18.dp))
                }
            }
        },
    )
}

/** Shared by the bulk move action and the detail sheet. */
@Composable
fun FolderPickDialog(
    folders: List<dev.zipshare.data.net.ZFolder>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move to folder") },
        text = {
            if (folders.isEmpty()) {
                Text("No folders yet. Create one on the Folders page first.")
            } else {
                LazyColumn {
                    items(folders, key = { it.id }) { folder ->
                        ListItem(
                            headlineContent = { Text(folder.name) },
                            modifier = Modifier.clickable { onPick(folder.id) },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
