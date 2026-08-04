package dev.zipshare.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.InsertPhoto
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import coil.compose.AsyncImage
import dev.zipshare.data.db.HistoryEntry
import dev.zipshare.data.net.ZFile
import dev.zipshare.data.net.isPlayable
import dev.zipshare.data.net.isTextLike
import dev.zipshare.data.net.previewUrl
import dev.zipshare.data.net.rawUrl
import dev.zipshare.data.model.formatLink
import dev.zipshare.data.net.shareUrl
import dev.zipshare.data.net.viewerUrl
import dev.zipshare.ui.browse.BrowseViewModel
import dev.zipshare.ui.browse.FileDetailSheet
import dev.zipshare.ui.search.AppSearchDialog
import dev.zipshare.ui.shell.AccountMenu
import dev.zipshare.ui.shell.LocalLinkFormat
import dev.zipshare.ui.shell.PullRefresh
import dev.zipshare.ui.shell.ShellTopBar
import dev.zipshare.ui.upload.UploadSheet
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

/**
 * Rows written before uploads stored an absolute link kept the server's route-relative `/u/name`,
 * which is unusable once copied out of the app. Resolving on read heals them without a migration.
 */
private fun absolute(url: String, base: String): String =
    if (url.startsWith("http://") || url.startsWith("https://")) {
        url
    } else {
        base.trimEnd('/') + if (url.startsWith("/")) url else "/$url"
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onMenu: () -> Unit,
    onSeeAllFiles: () -> Unit,
    onOpenImage: (name: String, previewUrl: String, shareUrl: String, playbackUrl: String?) -> Unit,
    onOpenText: (name: String, rawUrl: String, mime: String) -> Unit = { _, _, _ -> },
    pickFilesOnLaunch: Boolean = false,
    onPickFilesHandled: () -> Unit = {},
    onSettings: () -> Unit = {},
    onServerSettings: () -> Unit = {},
    onAccountSettings: () -> Unit = {},
    /** Any route in the shell, so search can jump straight to what it found. */
    onNavigate: (String) -> Unit = {},
    vm: HomeViewModel = hiltViewModel(),
    // The file actions all live on BrowseViewModel already; reusing it here is what keeps the
    // sheet identical to the one on the Files page instead of a second copy that drifts.
    browseVm: BrowseViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val cfg by vm.appSettings.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    val linkFormat = LocalLinkFormat.current
    val recentsScroll = rememberLazyListState()

    var confirmClearHistory by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var detailFor by remember { mutableStateOf<ZFile?>(null) }

    // The dashboard reloads every time it becomes visible - returning from an upload, a file
    // screen or another app - rather than only when the profile changes. repeatOnLifecycle fires
    // again on each RESUMED, which a plain LaunchedEffect(Unit) would not.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, state.active?.id) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (state.active != null) vm.refresh()
        }
    }

    // New uploads are prepended, and LazyRow keeps its scroll anchor - so without this the newest
    // file lands off-screen to the left and looks like nothing refreshed.
    LaunchedEffect(state.recents.firstOrNull()?.id) {
        if (state.recents.isNotEmpty()) recentsScroll.animateScrollToItem(0)
    }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(30),
    ) { vm.filesPicked(it) }

    val documentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { vm.filesPicked(it) }

    // Launcher-shortcut / QS-tile entry: open the picker as soon as Home is up.
    LaunchedEffect(pickFilesOnLaunch) {
        if (pickFilesOnLaunch) {
            onPickFilesHandled()
            documentPicker.launch(arrayOf("*/*"))
        }
    }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            vm.dismissError()
        }
    }

    val base = state.active?.baseUrl.orEmpty()
    val browse by browseVm.state.collectAsStateWithLifecycle()

    // Tags and folders are what the sheet needs beyond the file itself.
    LaunchedEffect(state.active?.id) {
        if (state.active != null) {
            browseVm.loadTags()
            browseVm.loadFolders()
        }
    }

    // The sheet holds a snapshot; a refresh replaces the row, so re-read it by id to keep the
    // open sheet in step with an edit made from inside it.
    val detail = detailFor?.let { snap -> state.recents.firstOrNull { it.id == snap.id } ?: snap }
    if (detail != null) {
        FileDetailSheet(
            file = detail,
            baseUrl = base,
            allTags = browse.tags,
            folders = browse.folders,
            onDismiss = { detailFor = null },
            // Every mutation goes through BrowseViewModel, then Home reloads - the two view
            // models hold separate lists, so without the refresh the card would show stale data.
            onToggleFavourite = { browseVm.toggleFavourite(detail); vm.refresh() },
            onPatch = { name, maxViews, tags ->
                browseVm.patchFile(detail.id, name, maxViews, tags)
                vm.refresh()
            },
            onMove = { folder -> browseVm.moveToFolder(listOf(detail.id), folder); vm.refresh() },
            onCreateTag = browseVm::createTag,
            onEditTag = browseVm::editTag,
            onDeleteTag = browseVm::deleteTag,
            onSetPassword = { browseVm.setFilePassword(detail.id, it); vm.refresh() },
            onDelete = {
                browseVm.deleteFile(detail)
                detailFor = null
                vm.refresh()
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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            ShellTopBar(
                title = "Home",
                profiles = state.profiles,
                activeLabel = state.active?.label,
                onMenu = onMenu,
                onSelectProfile = vm::selectProfile,
                actions = {
                    IconButton(onClick = { searchOpen = true }) {
                        Icon(Icons.Filled.Search, "Search")
                    }
                },
                account = {
                    AccountMenu(
                        isAdmin = state.user?.role in setOf("ADMIN", "SUPERADMIN"),
                        onAccountSettings = onAccountSettings,
                    )
                },
            )
        },
        floatingActionButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExtendedFloatingActionButton(
                    onClick = { documentPicker.launch(arrayOf("*/*")) },
                    icon = { Icon(Icons.Filled.Folder, null) },
                    text = { Text("Files") },
                )
                ExtendedFloatingActionButton(
                    onClick = {
                        photoPicker.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageAndVideo,
                            ),
                        )
                    },
                    icon = { Icon(Icons.Filled.Add, null) },
                    text = { Text("Media") },
                )
            }
        },
    ) { padding ->
        PullRefresh(
            refreshing = state.syncing,
            onRefresh = vm::refresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        ) {
            // --- Welcome back, <user> ---
            item {
                Text(
                    buildString {
                        append("Welcome back")
                        state.user?.username?.let { append(", $it") }
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                val uploaded = state.stats?.filesUploaded
                Text(
                    when {
                        !state.profilesReady -> "Loading your servers..."
                        state.active == null -> "No server selected. Add one to get started."
                        state.syncing && uploaded == null -> "You have ... files uploaded."
                        uploaded != null -> "You have $uploaded files uploaded."
                        else -> "Could not read your stats."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            state.syncError?.let { err ->
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.CloudOff,
                            null,
                            Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            err,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }

            // --- Recent files ---
            if (cfg.showRecents) {
                item {
                    SectionHeader("Recent files") {
                        TextButton(onClick = onSeeAllFiles) { Text("View all files") }
                    }
                }
                item {
                    when {
                        state.syncing && state.recents.isEmpty() -> {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(3) { SkeletonBox(height = 250, modifier = Modifier.size(180.dp, 250.dp)) }
                            }
                        }

                        state.recents.isEmpty() -> Text(
                            "No recent files. The last ${cfg.recentCount} you upload will appear here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        else -> LazyRow(
                            state = recentsScroll,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(state.recents, key = { it.id }) { file ->
                                RecentFileCard(
                                    file = file,
                                    baseUrl = base,
                                    // Same sheet as the Files page: a tap here used to jump
                                    // straight to the viewer, which hid every action the file
                                    // has and did nothing at all for a non-previewable type.
                                    onOpen = { detailFor = file },
                                    onCopy = {
                                        clipboard.setText(
                                            AnnotatedString(
                                                formatLink(file.name, file.shareUrl(base), linkFormat),
                                            ),
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }

            // --- quota lines, exactly as the web dashboard words them ---
            val quota = state.user?.quota
            val stats = state.stats
            if (quota != null && stats != null) {
                item {
                    Column(Modifier.padding(top = 12.dp)) {
                        when {
                            quota.filesQuota == "BY_BYTES" && quota.maxBytes != null -> Text(
                                "You have used ${humanSize(stats.storageUsed)} out of ${quota.maxBytes} of storage",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            quota.maxFiles != null -> Text(
                                "You have uploaded ${stats.filesUploaded} files out of ${quota.maxFiles} allowed.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        quota.maxUrls?.let {
                            Text(
                                "You have created ${stats.urlsCreated} links out of $it allowed.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // --- Stats: the same eight cards the web dashboard shows ---
            if (cfg.showStats) {
                item { SectionHeader("Stats") }
                item {
                    Text(
                        "These statistics are based on your uploads only.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                }
                if (stats == null) {
                    items(4) {
                        SkeletonBox(
                            height = 84,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                        )
                    }
                } else {
                    val cards = listOf(
                        Triple("Files uploaded", stats.filesUploaded.toString(), Icons.AutoMirrored.Filled.InsertDriveFile),
                        Triple("Favorite files", stats.favoriteFiles.toString(), Icons.Filled.Star),
                        Triple("Storage used", humanSize(stats.storageUsed), Icons.Filled.SdStorage),
                        Triple("Average storage used", humanSize(stats.avgStorageUsed), Icons.Filled.SdStorage),
                        Triple("File views", stats.views.toString(), Icons.Filled.Visibility),
                        Triple("Average file views", stats.avgViews.roundToInt().toString(), Icons.Filled.Visibility),
                        Triple("Links created", stats.urlsCreated.toString(), Icons.Filled.Link),
                        Triple("Total link views", stats.urlViews.toString(), Icons.Filled.Link),
                    )
                    items(cards.chunked(2)) { row ->
                        Row(
                            Modifier.fillMaxWidth().padding(bottom = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            row.forEach { (title, value, icon) ->
                                StatCard(title, value, icon, Modifier.weight(1f))
                            }
                            if (row.size == 1) Box(Modifier.weight(1f))
                        }
                    }
                }
            }

            // --- File types ---
            if (cfg.showTypes && !stats?.sortTypeCount.isNullOrEmpty()) {
                item { SectionHeader("File types") }
                item { FileTypesTable(stats!!.sortTypeCount) }
            }

            // --- device-local upload history ---
            if (cfg.showLocalHistory) {
                item {
                    SectionHeader("On this device") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${entries.size} uploads",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (entries.isNotEmpty()) {
                                TextButton(onClick = { confirmClearHistory = true }) { Text("Clear") }
                            }
                        }
                    }
                }
                if (entries.isEmpty()) {
                    item {
                        Text(
                            "Uploads made from this device are recorded here, even offline.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(entries, key = { it.remoteId }) { entry ->
                        HistoryRow(
                            entry = entry,
                            onCopy = {
                                clipboard.setText(
                                    AnnotatedString(
                                        formatLink(entry.name, absolute(entry.remoteUrl, base), linkFormat),
                                    ),
                                )
                            },
                            onDelete = { vm.delete(entry) },
                        )
                    }
                }
            }
        }
        }
    }

    if (searchOpen) {
        AppSearchDialog(
            isAdmin = state.user?.role in setOf("ADMIN", "SUPERADMIN"),
            onDismiss = { searchOpen = false },
            onNavigate = onNavigate,
        )
    }

    if (confirmClearHistory) {
        AlertDialog(
            onDismissRequest = { confirmClearHistory = false },
            title = { Text("Clear this list?") },
            text = {
                Text(
                    "This only removes the upload record kept on this phone. Your files stay on " +
                        "the server and the links keep working.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.clearHistory()
                        confirmClearHistory = false
                    },
                ) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearHistory = false }) { Text("Cancel") }
            },
        )
    }

    if (state.sheetOpen) {
        UploadSheet(
            fileCount = state.pending.size,
            folders = state.folders,
            options = state.options,
            onOptionsChange = vm::updateOptions,
            onDismiss = vm::dismissSheet,
            onConfirm = vm::confirmUpload,
            onResetToDefaults = vm::resetOptionsToDefaults,
            fileNames = state.pendingNames,
            namesRedacted = state.namesRedacted,
        )
    }
}

@Composable
private fun HistoryRow(entry: HistoryEntry, onCopy: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (entry.mime.startsWith("image/")) {
            AsyncImage(
                model = entry.remoteUrl,
                contentDescription = null,
                modifier = Modifier.size(44.dp),
            )
        } else {
            Icon(Icons.Filled.InsertPhoto, null, Modifier.size(44.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                entry.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append(
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                            .format(Date(entry.ts)),
                    )
                    append(" - ")
                    append(humanSize(entry.size))
                    if (entry.pending) append(" - processing")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onCopy) { Text("Copy") }
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Delete") }
    }
}
