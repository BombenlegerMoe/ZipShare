package dev.zipshare.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PostAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.zipshare.ui.Routes

/**
 * One thing you can search for: a screen, or a setting that lives on one.
 *
 * [where] is shown under the title rather than being decoration - a setting cannot be deep-linked
 * to its own row, so search takes you to the screen holding it and this is what tells you where to
 * look once you arrive. Claiming otherwise would be worse than saying nothing.
 */
data class SearchEntry(
    val title: String,
    val where: String,
    val route: String,
    val icon: ImageVector,
    /** Synonyms and the words people actually type; never shown, only matched. */
    val keywords: List<String> = emptyList(),
    val adminOnly: Boolean = false,
)

/**
 * Everything reachable in the app, flattened.
 *
 * Hand-written rather than derived from the UI: the settings screens build their rows inline, so
 * there is nothing to reflect over, and a generated index would only be as good as whatever
 * labels happened to be in scope. The cost is that adding a setting means adding a line here.
 */
val appSearchIndex: List<SearchEntry> = listOf(
    // --- destinations ---
    SearchEntry("Home", "Dashboard", Routes.HOME, Icons.Filled.Home, listOf("dashboard", "start", "recent")),
    SearchEntry("Files", "Browse", Routes.FILES, Icons.AutoMirrored.Filled.InsertDriveFile, listOf("uploads", "images", "browse", "gallery")),
    SearchEntry("Folders", "Browse", Routes.FOLDERS, Icons.Filled.Folder, listOf("albums", "directories")),
    SearchEntry("Short links", "Browse", Routes.URLS, Icons.Filled.Link, listOf("urls", "shorten", "vanity")),
    SearchEntry("Upload text", "Upload", Routes.UPLOAD_TEXT, Icons.Filled.PostAdd, listOf("paste", "snippet", "code", "note")),
    SearchEntry("Servers", "Connection", Routes.SERVERS, Icons.Filled.Dns, listOf("profiles", "instance", "add server", "token", "certificate", "pin", "switch")),
    SearchEntry("Diagnostic", "Troubleshooting", Routes.DIAGNOSTIC, Icons.Filled.BugReport, listOf("logs", "history", "export", "import", "backup", "server version", "debug")),
    SearchEntry("Metrics", "Administrator", Routes.METRICS, Icons.AutoMirrored.Filled.ShowChart, listOf("stats", "charts", "usage"), adminOnly = true),
    SearchEntry("Users", "Administrator", Routes.USERS, Icons.Filled.Group, listOf("accounts", "roles", "quota"), adminOnly = true),
    SearchEntry("Invites", "Administrator", Routes.INVITES, Icons.Filled.Tag, listOf("invite link", "registration", "signup"), adminOnly = true),
    SearchEntry("Server actions", "Administrator", Routes.ADMIN_ACTIONS, Icons.Filled.Bolt, listOf("clear temp", "zero files", "requery size", "thumbnails", "maintenance"), adminOnly = true),
    SearchEntry("Server settings", "Administrator", Routes.ADMIN_SETTINGS, Icons.Filled.Tune, listOf("instance settings", "core", "chunks", "discord", "oauth", "ratelimit", "tasks", "website", "features"), adminOnly = true),

    // --- account ---
    SearchEntry("Avatar", "Account settings", Routes.ACCOUNT, Icons.Filled.AccountCircle, listOf("profile picture", "photo")),
    SearchEntry("Change username", "Account settings", Routes.ACCOUNT, Icons.Filled.AccountCircle, listOf("rename", "name")),
    SearchEntry("Change password", "Account settings", Routes.ACCOUNT, Icons.Filled.AccountCircle, listOf("credentials")),
    SearchEntry("Logged-in devices", "Account settings", Routes.ACCOUNT, Icons.Filled.AccountCircle, listOf("sessions", "sign out others", "revoke")),
    SearchEntry("Viewing files", "Account settings", Routes.ACCOUNT, Icons.Filled.AccountCircle, listOf("embed", "opengraph", "preview", "discord embed", "view page")),

    // --- app settings ---
    SearchEntry("App lock", "Settings > Security", Routes.SETTINGS, Icons.Filled.Lock, listOf("biometric", "fingerprint", "face", "pin", "device credential", "lock")),
    SearchEntry("Lock after", "Settings > Security", Routes.SETTINGS, Icons.Filled.Lock, listOf("timeout", "background", "auto lock")),
    SearchEntry("Two-factor authentication", "Settings > Security", Routes.SETTINGS, Icons.Filled.Lock, listOf("2fa", "totp", "authenticator", "otp", "mfa")),
    SearchEntry("Theme", "Settings > Appearance", Routes.SETTINGS, Icons.Filled.Palette, listOf("dark mode", "light", "system", "appearance")),
    SearchEntry("Dynamic color", "Settings > Appearance", Routes.SETTINGS, Icons.Filled.Palette, listOf("material you", "wallpaper", "colour", "color")),
    SearchEntry("Sharing link format", "Settings > Sharing", Routes.SETTINGS, Icons.Filled.Share, listOf("markdown", "plain", "view page", "copy link", "clipboard", "embed")),
    SearchEntry("Recent uploads to sync", "Settings > Dashboard", Routes.SETTINGS, Icons.Filled.Settings, listOf("recent count", "how many")),
    SearchEntry("Show recent files", "Settings > Dashboard", Routes.SETTINGS, Icons.Filled.Settings, listOf("hide recent")),
    SearchEntry("Show stat cards", "Settings > Dashboard", Routes.SETTINGS, Icons.Filled.Settings, listOf("statistics", "stats")),
    SearchEntry("Show file types table", "Settings > Dashboard", Routes.SETTINGS, Icons.Filled.Settings, listOf("types")),
    SearchEntry("Show on-device history", "Settings > Dashboard", Routes.SETTINGS, Icons.Filled.Settings, listOf("local history")),
    SearchEntry("Upload notifications", "Settings > Notifications", Routes.SETTINGS, Icons.Filled.Notifications, listOf("progress", "completed", "failed", "silent", "channels")),
    SearchEntry("Chunked upload", "Settings > Uploads", Routes.SETTINGS, Icons.Filled.Upload, listOf("chunk size", "threshold", "partial", "large files", "resumable")),
    SearchEntry("Skip the upload sheet", "Settings > Uploads", Routes.SETTINGS, Icons.Filled.Upload, listOf("upload immediately", "no options", "quick")),
    SearchEntry("Image compression", "Settings > Upload defaults", Routes.SETTINGS, Icons.Filled.Upload, listOf("auto", "jpeg quality", "png quality", "webp", "jxl", "shrink", "re-encode")),
    SearchEntry("Name format", "Settings > Upload defaults", Routes.SETTINGS, Icons.Filled.Upload, listOf("random", "uuid", "date", "gfycat", "filename")),
    SearchEntry("Default folder", "Settings > Upload defaults", Routes.SETTINGS, Icons.Filled.Upload, listOf("folder")),
    SearchEntry("Upload password", "Settings > Upload defaults", Routes.SETTINGS, Icons.Filled.Upload, listOf("protect", "passphrase")),
    SearchEntry("Max views", "Settings > Upload defaults", Routes.SETTINGS, Icons.Filled.Upload, listOf("view limit", "burn after")),
    SearchEntry("Expiry", "Settings > Upload defaults", Routes.SETTINGS, Icons.Filled.Upload, listOf("deletes at", "expiration", "delete after")),
    SearchEntry("Keep original name", "Settings > Upload defaults", Routes.SETTINGS, Icons.Filled.Upload, listOf("original name")),
    SearchEntry("Return domain", "Settings > Upload defaults", Routes.SETTINGS, Icons.Filled.Upload, listOf("domain", "cname")),

    // --- diagnostic ---
    SearchEntry("Upload history", "Diagnostic", Routes.DIAGNOSTIC, Icons.Filled.BugReport, listOf("past uploads", "clear history")),
    SearchEntry("Activity log", "Diagnostic", Routes.DIAGNOSTIC, Icons.Filled.BugReport, listOf("logs", "export log", "auth log", "troubleshoot")),
    SearchEntry("Backup and import settings", "Diagnostic", Routes.DIAGNOSTIC, Icons.Filled.BugReport, listOf("export settings", "import settings", "backup", "restore")),
    SearchEntry("Zipline server version", "Diagnostic", Routes.DIAGNOSTIC, Icons.Filled.BugReport, listOf("version", "update", "upstream")),
)

/**
 * Ranked match over [entries]. Kept pure - no Compose, no Android - so the ranking can be tested.
 *
 * A title that starts with the query beats one that merely contains it, which beats a keyword-only
 * hit. Without that, typing "files" put "Show file types table" above "Files".
 */
fun searchEntries(
    query: String,
    isAdmin: Boolean,
    entries: List<SearchEntry> = appSearchIndex,
): List<SearchEntry> {
    val visible = entries.filter { !it.adminOnly || isAdmin }
    val q = query.trim().lowercase()
    if (q.isEmpty()) return visible

    fun rank(e: SearchEntry): Int {
        val title = e.title.lowercase()
        return when {
            title.startsWith(q) -> 0
            title.contains(q) -> 1
            e.where.lowercase().contains(q) -> 2
            e.keywords.any { it.contains(q) } -> 3
            else -> -1
        }
    }

    return visible
        .map { it to rank(it) }
        .filter { it.second >= 0 }
        .sortedWith(compareBy({ it.second }, { it.first.title }))
        .map { it.first }
}

/** Full-screen search over every screen and setting, opened from the Home top bar. */
@Composable
fun AppSearchDialog(
    isAdmin: Boolean,
    onDismiss: () -> Unit,
    onNavigate: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    val results = searchEntries(query, isAdmin)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close search")
                    }
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Search screens and settings") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Filled.Search, null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(Icons.Filled.Close, "Clear")
                                }
                            }
                        },
                        colors = TextFieldDefaults.colors(
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
                HorizontalDivider()

                if (results.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopCenter) {
                        Text(
                            "Nothing matches \"$query\".",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(results, key = { it.title + it.where }) { entry ->
                            SearchResultRow(entry) {
                                onDismiss()
                                onNavigate(entry.route)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(entry: SearchEntry, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(entry.icon, null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.padding(start = 16.dp)) {
            Text(entry.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                entry.where,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
