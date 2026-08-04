package dev.zipshare.ui.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.PostAdd
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShieldMoon
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.zipshare.data.model.LinkFormat
import dev.zipshare.data.model.Profile
import dev.zipshare.ui.search.SearchAction

/** Mirrors the Zipline web sidebar. Admin entries are hidden for non-admin roles, as on the web. */
enum class NavItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val adminOnly: Boolean = false,
) {
    HOME("home", "Home", Icons.Filled.Home),
    METRICS("metrics", "Metrics", Icons.AutoMirrored.Filled.ShowChart),
    FILES("files", "Files", Icons.AutoMirrored.Filled.InsertDriveFile),
    FOLDERS("folders", "Folders", Icons.Filled.Folder),
    UPLOAD_TEXT("upload/text", "Upload text", Icons.Filled.PostAdd),
    QUEUE("upload/queue", "Upload queue", Icons.Filled.Schedule),
    URLS("urls", "URLs", Icons.Filled.Link),
    ADMIN_SETTINGS("admin/settings", "Settings", Icons.Filled.Tune, adminOnly = true),
    ADMIN_ACTIONS("admin/actions", "Actions", Icons.Filled.Bolt, adminOnly = true),
    USERS("users", "Users", Icons.Filled.Group, adminOnly = true),
    INVITES("invites", "Invites", Icons.Filled.Tag, adminOnly = true),
    SERVERS("servers", "Servers", Icons.Filled.Dns),
    SETTINGS("settings", "Settings", Icons.Filled.Settings),
    DIAGNOSTIC("diagnostic", "Diagnostic", Icons.Filled.BugReport),
}

fun isAdministrator(role: String?): Boolean = role == "ADMIN" || role == "SUPERADMIN"

/**
 * "username - ROLE" for the shell top bar, provided once by AppNav.
 *
 * A CompositionLocal rather than a parameter because all ten screens that use [ShellTopBar] would
 * otherwise have to thread the same value through, and only some of their view models even load
 * the user - most would be passing null.
 */
val LocalSignedInUser = compositionLocalOf<String?> { null }

/**
 * The shape every "Copy link" button writes to the clipboard. Same reasoning as
 * [LocalSignedInUser]: it is one setting read by five screens, two of which (the viewers) have no
 * view model at all to thread it through.
 */
val LocalLinkFormat = compositionLocalOf { LinkFormat.PLAIN }

/**
 * Navigation, for the search action that now sits in every [ShellTopBar].
 *
 * Same reasoning again: search is reachable from eleven screens, and threading a nav callback
 * through every one of them - most of which never navigate anywhere themselves - is a parameter
 * on eleven signatures to serve one button.
 */
val LocalNavigate = compositionLocalOf<(String) -> Unit> { {} }

/** Whether the signed-in account is an admin, so search can hide destinations it cannot open. */
val LocalIsAdmin = compositionLocalOf { false }

@Composable
fun ZiplineDrawerSheet(
    current: String,
    role: String?,
    onNavigate: (String) -> Unit,
) {
    ModalDrawerSheet {
        // No header: the app name, server and account all live in the top bar now, and dropping
        // it here is what lets the last entry fit without scrolling on a normal phone.
        Column(Modifier.verticalScroll(rememberScrollState()).padding(top = 12.dp)) {
            // The bottom group is placed by hand below, so it is excluded here.
            val bottom = setOf(NavItem.SERVERS, NavItem.SETTINGS, NavItem.DIAGNOSTIC)
            val general = NavItem.entries.filter { !it.adminOnly && it !in bottom }
            general.forEach { item ->
                DrawerRow(item, current, onNavigate)
            }

            if (isAdministrator(role)) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(
                    Modifier.padding(start = 28.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.ShieldMoon,
                        null,
                        Modifier.padding(end = 8.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Administrator",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                NavItem.entries.filter { it.adminOnly }.forEach { DrawerRow(it, current, onNavigate) }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            DrawerRow(NavItem.SERVERS, current, onNavigate)
            DrawerRow(NavItem.SETTINGS, current, onNavigate)
            DrawerRow(NavItem.DIAGNOSTIC, current, onNavigate)
        }
    }
}

@Composable
private fun DrawerRow(item: NavItem, current: String, onNavigate: (String) -> Unit) {
    NavigationDrawerItem(
        icon = { Icon(item.icon, null) },
        label = { Text(item.label) },
        selected = current == item.route,
        onClick = { onNavigate(item.route) },
        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
    )
}

/**
 * Shared top bar: hamburger, the page title and the active-server switcher.
 * Every page in the shell uses this so navigation feels the same as the web layout.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShellTopBar(
    title: String,
    profiles: List<Profile>,
    activeLabel: String?,
    onMenu: () -> Unit,
    onSelectProfile: (String) -> Unit,
    actions: @Composable () -> Unit = {},
    /** The account avatar, shown at the far right like Zipline's web header. */
    account: (@Composable () -> Unit)? = null,
) {
    var switcherOpen by remember { mutableStateOf(false) }
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onMenu) { Icon(Icons.Filled.Menu, "Menu") }
        },
        // Three stacked lines do not fit the 64dp default; this is their natural height plus the
        // bar's own padding, with nothing left over to spread them apart.
        expandedHeight = 80.dp,
        title = {
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Box {
                    // A plain clickable Text, not a TextButton: the button's 36dp minimum height
                    // is what pushed the three lines apart.
                    Text(
                        activeLabel ?: "No server",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { switcherOpen = true },
                    )
                    DropdownMenu(
                        expanded = switcherOpen,
                        onDismissRequest = { switcherOpen = false },
                    ) {
                        profiles.forEach { p ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (p.authenticated) p.label else "${p.label} - token invalid",
                                    )
                                },
                                onClick = {
                                    onSelectProfile(p.id)
                                    switcherOpen = false
                                },
                            )
                        }
                    }
                }
                LocalSignedInUser.current?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        // No refresh button: every list refreshes by pulling down, as elsewhere on Android.
        actions = {
            actions()
            account?.invoke()
            // Last, so it lands in the same place on every screen. Placing it before the avatar
            // put it 100px further left on Home than everywhere else, because Home is the only
            // screen with an avatar - which defeats the point of having it in a fixed spot.
            SearchAction()
        },
    )
}

/**
 * Swipe-down-to-refresh wrapper used by every list screen, so the gesture behaves identically
 * across the app and each screen only has to say what "refresh" means for it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PullRefresh(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = modifier) {
        content()
    }
}

/**
 * Empty/error placeholder. It is deliberately scrollable: `PullToRefreshBox` only sees a swipe
 * that arrives through nested scroll, so a plain Box here would make pull-to-refresh dead on
 * exactly the screens where you most want to retry - the empty ones.
 */
@Composable
fun EmptyOrError(message: String, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
