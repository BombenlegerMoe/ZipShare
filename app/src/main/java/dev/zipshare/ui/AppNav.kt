package dev.zipshare.ui

import android.net.Uri
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.zipshare.MainActivity
import dev.zipshare.ui.account.AccountSettingsScreen
import dev.zipshare.ui.admin.InvitesScreen
import dev.zipshare.ui.admin.MetricsScreen
import dev.zipshare.ui.admin.ServerActionsScreen
import dev.zipshare.ui.admin.ServerSettingsScreen
import dev.zipshare.ui.browse.BrowseViewModel
import dev.zipshare.ui.browse.FilesScreen
import dev.zipshare.ui.browse.FoldersScreen
import dev.zipshare.ui.browse.UrlsScreen
import dev.zipshare.ui.browse.UsersScreen
import dev.zipshare.ui.diagnostic.DiagnosticScreen
import dev.zipshare.ui.home.HomeScreen
import dev.zipshare.ui.servers.ServerEditScreen
import dev.zipshare.ui.servers.ServersScreen
import dev.zipshare.ui.servers.ServersViewModel
import dev.zipshare.ui.settings.SettingsScreen
import dev.zipshare.ui.shell.LocalIsAdmin
import dev.zipshare.ui.shell.LocalLinkFormat
import dev.zipshare.ui.shell.LocalNavigate
import dev.zipshare.ui.shell.LocalSignedInUser
import dev.zipshare.ui.shell.NavItem
import dev.zipshare.ui.shell.isAdministrator
import dev.zipshare.ui.shell.ZiplineDrawerSheet
import dev.zipshare.ui.upload.QueueScreen
import dev.zipshare.ui.upload.UploadTextScreen
import dev.zipshare.ui.viewer.ImageViewerScreen
import dev.zipshare.ui.viewer.TextViewerScreen
import dev.zipshare.ui.welcome.WelcomeScreen
import kotlinx.coroutines.launch

object Routes {
    const val HOME = "home"
    const val FILES = "files"
    const val FOLDERS = "folders"
    const val URLS = "urls"
    const val USERS = "users"
    const val SERVERS = "servers"
    const val SERVER_EDIT = "servers/edit"
    const val SETTINGS = "settings"
    const val FOLDER_FILES = "files/folder"
    const val METRICS = "metrics"
    const val UPLOAD_TEXT = "upload/text"
    const val QUEUE = "upload/queue"
    const val ADMIN_SETTINGS = "admin/settings"
    const val ADMIN_ACTIONS = "admin/actions"
    const val INVITES = "invites"
    const val VIEWER = "viewer"
    const val ACCOUNT = "account"
    const val DIAGNOSTIC = "diagnostic"
    const val WELCOME = "welcome"
    const val TEXT_VIEWER = "viewer/text"
}

/** Every argument is encoded because they are full URLs and free-form names. */
fun textViewerRoute(name: String, rawUrl: String, mime: String): String =
    "${Routes.TEXT_VIEWER}?name=${Uri.encode(name)}" +
        "&raw=${Uri.encode(rawUrl)}" +
        "&mime=${Uri.encode(mime)}"

/**
 * First-run flow: just the sign-in screen. Kept as its own tiny NavHost so onboarding never shows
 * a navigation drawer for an app that has no server yet. As soon as a profile is saved, [AppNav]
 * swaps to the real shell.
 */
@Composable
private fun WelcomeNav() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.WELCOME) {
        composable(Routes.WELCOME) { entry ->
            WelcomeScreen(vm = hiltViewModel(entry))
        }
    }
}

/** Builds the viewer route; every argument is encoded because they are full URLs. */
fun viewerRoute(
    name: String,
    previewUrl: String,
    shareUrl: String,
    playbackUrl: String? = null,
): String =
    "${Routes.VIEWER}?name=${Uri.encode(name)}" +
        "&preview=${Uri.encode(previewUrl)}" +
        "&share=${Uri.encode(shareUrl)}" +
        "&play=${Uri.encode(playbackUrl.orEmpty())}"

@Composable
fun AppNav(startAction: String? = null) {
    val nav = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // One-shot: saved across rotation so a config change does not re-fire the shortcut action.
    var pendingAction by rememberSaveable { mutableStateOf(startAction) }
    LaunchedEffect(Unit) {
        if (pendingAction == MainActivity.ACTION_UPLOAD_TEXT) {
            pendingAction = null
            nav.navigate(Routes.UPLOAD_TEXT) { launchSingleTop = true }
        }
    }

    // Shell-level VM: supplies the drawer header and the admin gate.
    val shellVm: BrowseViewModel = hiltViewModel()
    val shell by shellVm.state.collectAsStateWithLifecycle()

    LaunchedEffect(shell.active?.id) { if (shell.active != null) shellVm.loadMe() }

    // Nothing configured yet: sign-in is the whole app, not a page inside it. Gated on
    // profilesReady so a cold start does not flash this before profiles load off disk.
    if (shell.profilesReady && shell.profiles.isEmpty()) {
        WelcomeNav()
        return
    }

    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route?.substringBefore('?').orEmpty()

    fun go(route: String) {
        scope.launch { drawerState.close() }
        if (route != currentRoute) {
            nav.navigate(route) {
                popUpTo(Routes.HOME) { inclusive = false }
                launchSingleTop = true
            }
        }
    }

    val openMenu: () -> Unit = { scope.launch { drawerState.open() } }

    // Provided once here so every ShellTopBar can show who is signed in without ten screens
    // each having to load and pass the same user.
    val linkFormat by shellVm.linkFormat.collectAsStateWithLifecycle()

    CompositionLocalProvider(
        LocalSignedInUser provides shell.me?.let { "${it.username} - ${it.role}" },
        LocalLinkFormat provides linkFormat,
        LocalNavigate provides ::go,
        LocalIsAdmin provides isAdministrator(shell.me?.role),
    ) {
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ZiplineDrawerSheet(
                current = currentRoute,
                role = shell.me?.role,
                onNavigate = ::go,
            )
        },
    ) {
        NavHost(navController = nav, startDestination = Routes.HOME) {
            composable(Routes.HOME) {
                HomeScreen(
                    onMenu = openMenu,
                    onSeeAllFiles = { go(NavItem.FILES.route) },
                    onOpenImage = { n, p, s, play -> nav.navigate(viewerRoute(n, p, s, play)) },
                    onOpenText = { n, raw, mime -> nav.navigate(textViewerRoute(n, raw, mime)) },
                    pickFilesOnLaunch = pendingAction == MainActivity.ACTION_UPLOAD_FILE,
                    onPickFilesHandled = { pendingAction = null },
                    onSettings = { go(Routes.SETTINGS) },
                    onServerSettings = { go(Routes.ADMIN_SETTINGS) },
                    onAccountSettings = { nav.navigate(Routes.ACCOUNT) },
                )
            }
            // `focus` is optional: the drawer navigates to the bare route, search appends the id of
            // the row it found so the screen can scroll to it and flash it.
            composable(
                "${Routes.ACCOUNT}?focus={focus}",
                arguments = listOf(navArgument("focus") { nullable = true; defaultValue = null }),
            ) { entry ->
                AccountSettingsScreen(
                    onBack = { nav.popBackStack() },
                    focus = entry.arguments?.getString("focus"),
                )
            }
            composable(
                "${Routes.DIAGNOSTIC}?focus={focus}",
                arguments = listOf(navArgument("focus") { nullable = true; defaultValue = null }),
            ) { entry ->
                DiagnosticScreen(onMenu = openMenu, focus = entry.arguments?.getString("focus"))
            }
            composable(Routes.FILES) {
                FilesScreen(
                    onMenu = openMenu,
                    onOpenImage = { n, p, s, play -> nav.navigate(viewerRoute(n, p, s, play)) },
                    onOpenText = { n, raw, mime -> nav.navigate(textViewerRoute(n, raw, mime)) },
                )
            }
            composable("${Routes.FOLDER_FILES}?id={id}&name={name}") { entry ->
                FilesScreen(
                    onMenu = openMenu,
                    folderId = entry.arguments?.getString("id")?.takeIf { it.isNotBlank() },
                    folderName = entry.arguments?.getString("name")?.takeIf { it.isNotBlank() },
                    onOpenImage = { n, p, s, play -> nav.navigate(viewerRoute(n, p, s, play)) },
                    onOpenText = { n, raw, mime -> nav.navigate(textViewerRoute(n, raw, mime)) },
                )
            }
            composable(Routes.FOLDERS) {
                FoldersScreen(
                    onMenu = openMenu,
                    onOpenFolder = { id, name ->
                        nav.navigate("${Routes.FOLDER_FILES}?id=$id&name=$name")
                    },
                )
            }
            composable(Routes.URLS) { UrlsScreen(onMenu = openMenu) }
            composable(Routes.USERS) { UsersScreen(onMenu = openMenu) }
            composable(Routes.METRICS) { MetricsScreen(onMenu = openMenu) }
            composable(Routes.QUEUE) { QueueScreen(onMenu = openMenu) }
            composable(Routes.UPLOAD_TEXT) { UploadTextScreen(onMenu = openMenu) }
            composable(
                "${Routes.ADMIN_SETTINGS}?focus={focus}",
                arguments = listOf(navArgument("focus") { nullable = true; defaultValue = null }),
            ) { entry ->
                ServerSettingsScreen(onMenu = openMenu, focus = entry.arguments?.getString("focus"))
            }
            composable(Routes.ADMIN_ACTIONS) { ServerActionsScreen(onMenu = openMenu) }
            composable(Routes.INVITES) { InvitesScreen(onMenu = openMenu) }
            composable(Routes.SERVERS) {
                ServersScreen(
                    onMenu = openMenu,
                    onEdit = { id -> nav.navigate("${Routes.SERVER_EDIT}?id=${id.orEmpty()}") },
                )
            }
            composable("${Routes.SERVER_EDIT}?id={id}") { entry ->
                ServerEditScreen(
                    profileId = entry.arguments?.getString("id")?.takeIf { it.isNotBlank() },
                    onDone = { nav.popBackStack() },
                    vm = hiltViewModel(entry),
                )
            }
            composable("${Routes.TEXT_VIEWER}?name={name}&raw={raw}&mime={mime}") { entry ->
                val args = entry.arguments
                TextViewerScreen(
                    name = Uri.decode(args?.getString("name").orEmpty()),
                    rawUrl = Uri.decode(args?.getString("raw").orEmpty()),
                    mime = Uri.decode(args?.getString("mime").orEmpty()).ifBlank { "text/plain" },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(
                "${Routes.SETTINGS}?focus={focus}",
                arguments = listOf(navArgument("focus") { nullable = true; defaultValue = null }),
            ) { entry ->
                // Username comes from the shell rather than a second /api/user call; it only
                // labels the entry in the authenticator app.
                SettingsScreen(
                    onMenu = openMenu,
                    username = shell.me?.username,
                    focus = entry.arguments?.getString("focus"),
                )
            }
            composable("${Routes.VIEWER}?name={name}&preview={preview}&share={share}&play={play}") { entry ->
                val args = entry.arguments
                ImageViewerScreen(
                    name = Uri.decode(args?.getString("name").orEmpty()),
                    previewUrl = Uri.decode(args?.getString("preview").orEmpty()),
                    shareUrl = Uri.decode(args?.getString("share").orEmpty()),
                    playbackUrl = Uri.decode(args?.getString("play").orEmpty()).takeIf { it.isNotBlank() },
                    onBack = { nav.popBackStack() },
                )
            }
        }
    }
    }
}
