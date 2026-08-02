package dev.zipshare.ui.shell

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zipshare.ui.rememberDataUrlBitmap

/**
 * The account avatar and its menu, mirroring Zipline's web header: who you are, account settings,
 * copy token, refresh token, sign out.
 */
@Composable
fun AccountMenu(
    isAdmin: Boolean,
    onAccountSettings: () -> Unit = {},
    vm: AccountViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    var confirmRefresh by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.load() }

    if (confirmRefresh) {
        AlertDialog(
            onDismissRequest = { confirmRefresh = false },
            title = { Text("Regenerate token?") },
            text = {
                Text(
                    "A new API token is issued and the current one stops working immediately. " +
                        "This phone updates itself, but any other device or script using the old " +
                        "token will need the new one.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmRefresh = false; vm.refreshToken() }) {
                    Text("Regenerate")
                }
            },
            dismissButton = { TextButton(onClick = { confirmRefresh = false }) { Text("Cancel") } },
        )
    }
    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out of ${state.serverLabel ?: "this server"}?") },
            text = {
                Text(
                    "The saved token is removed from this device. Nothing on the server changes, " +
                        "and you can sign in again at any time.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmSignOut = false; vm.signOut() }) { Text("Sign out") }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
    }

    Box {
        Avatar(
            avatar = state.user?.avatar,
            username = state.user?.username,
            modifier = Modifier.padding(end = 8.dp).clickable { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(
                buildString {
                    append(state.user?.username ?: "Signed in")
                    if (isAdmin) append(" (Administrator)")
                },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            HorizontalDivider()
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Filled.ManageAccounts, null) },
                text = { Text("Account settings") },
                onClick = { open = false; onAccountSettings() },
            )
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                text = { Text("Copy token") },
                // Marked sensitive by the helper, so it stays out of clipboard previews.
                onClick = { vm.copyToken(); open = false },
            )
            DropdownMenuItem(
                leadingIcon = {
                    Icon(Icons.Filled.Refresh, null, tint = MaterialTheme.colorScheme.error)
                },
                text = { Text("Refresh token", color = MaterialTheme.colorScheme.error) },
                enabled = !state.busy,
                onClick = { open = false; confirmRefresh = true },
            )
            // Settings and Server settings deliberately absent: both already live in the
            // navigation drawer, and duplicating them here only adds a second path to the same
            // screens.
            HorizontalDivider()
            DropdownMenuItem(
                leadingIcon = {
                    Icon(
                        Icons.AutoMirrored.Filled.Logout,
                        null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
                text = { Text("Sign out", color = MaterialTheme.colorScheme.error) },
                onClick = { open = false; confirmSignOut = true },
            )
        }
    }
}

/**
 * The server sends the avatar as a base64 data URL rather than a fetchable path, so it is decoded
 * here. Falls back to the first letter of the username, like the web dashboard does.
 */
@Composable
private fun Avatar(avatar: String?, username: String?, modifier: Modifier = Modifier) {
    val bitmap = rememberDataUrlBitmap(avatar)

    Box(
        modifier
            .size(32.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Account",
                contentScale = ContentScale.Crop,
                // clip, not just a circular background: without it the bitmap paints as a square
                // over the rounded backdrop.
                modifier = Modifier.size(32.dp).clip(CircleShape),
            )
        } else {
            Text(
                username?.firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}
