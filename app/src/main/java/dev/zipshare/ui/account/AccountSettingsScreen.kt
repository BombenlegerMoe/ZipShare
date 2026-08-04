package dev.zipshare.ui.account

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zipshare.data.net.ZSession
import dev.zipshare.data.net.ZView
import dev.zipshare.ui.rememberDataUrlBitmap
import dev.zipshare.ui.shell.AccountViewModel
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/**
 * Decodes the picked image at no more than [AVATAR_MAX_PX] on its longest side and re-encodes it
 * small enough to travel as a data URL. Returns null when the uri holds nothing decodable.
 *
 * Zipline takes the avatar as a data URL on `PATCH /api/user`, so base64 is the contract and
 * cannot be swapped for multipart - but it is applied to the downscaled bytes rather than the
 * original. Reading a 12 MP photo whole and then expanding it by a third on encoding is a large
 * transient allocation for what ends up a thumbnail, and was the most likely cause of the failure
 * that this screen used to discard in silence.
 *
 * PNG is kept for images with alpha; flattening a transparent avatar to JPEG would paint the
 * background black.
 */
private fun avatarDataUrl(context: Context, uri: Uri): String? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        ?: return null
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > AVATAR_MAX_PX) sample *= 2

    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    val bitmap = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, options)
    } ?: return null

    return try {
        val alpha = bitmap.hasAlpha()
        val format = if (alpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        var quality = 90
        var encoded: ByteArray
        do {
            val out = ByteArrayOutputStream()
            bitmap.compress(format, quality, out)
            encoded = out.toByteArray()
            quality -= 15
            // Quality is ignored for PNG, so re-compressing it would loop without shrinking.
        } while (!alpha && encoded.size > AVATAR_MAX_BYTES && quality >= 30)

        val mime = if (alpha) "image/png" else "image/jpeg"
        "data:$mime;base64," + Base64.encodeToString(encoded, Base64.NO_WRAP)
    } finally {
        bitmap.recycle()
    }
}

private const val AVATAR_MAX_PX = 512
private const val AVATAR_MAX_BYTES = 512_000

/**
 * Everything Zipline's web `/dashboard/settings` page offers for your own account: avatar,
 * username, password, the devices you are signed in on, and the view-route settings.
 *
 * Reached from the avatar menu rather than the drawer, because it is about *you* rather than
 * about the app or the server - which is also where the web dashboard puts it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSettingsScreen(onBack: () -> Unit, vm: AccountViewModel = hiltViewModel()) {
    // No FLAG_SECURE here, deliberately. It guards screens that render a credential in the clear
    // - the token in the server editor, the TOTP secret during enrollment. Every password field
    // on this screen is masked, so a screenshot would capture nothing worth hiding.
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { vm.load(); vm.loadSessions() }
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }

    val pickAvatar = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        // Zipline stores the avatar as a data URL, so the bytes are read here rather than
        // uploaded as multipart. The photo picker grants read access without any permission.
        uri?.let {
            runCatching { avatarDataUrl(context, it) }
                .onSuccess { dataUrl ->
                    if (dataUrl == null) {
                        scope.launch { snackbar.showSnackbar("That file could not be read as an image.") }
                    } else {
                        vm.setAvatar(dataUrl)
                    }
                }
                // Previously absent, so a failure here did nothing at all: the user picked an
                // image, no avatar appeared, and no error explained why.
                .onFailure { scope.launch { snackbar.showSnackbar("Could not read that image.") } }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                title = { Text("Account settings") },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // --- avatar ---
            Text("Avatar", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                BigAvatar(state.user?.avatar, state.user?.username)
                Column(Modifier.padding(start = 16.dp)) {
                    Text(
                        state.user?.username ?: "-",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        state.user?.role ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !state.busy,
                    onClick = {
                        pickAvatar.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                ) { Text(if (state.user?.avatar == null) "Upload avatar" else "Change avatar") }
                if (state.user?.avatar != null) {
                    OutlinedButton(enabled = !state.busy, onClick = vm::removeAvatar) {
                        Text("Remove")
                    }
                }
            }

            HorizontalDivider()
            UsernameSection(current = state.user?.username, busy = state.busy, onSave = vm::changeUsername)

            HorizontalDivider()
            PasswordSection(busy = state.busy, onSave = vm::changePassword)

            HorizontalDivider()
            SessionsSection(state.sessions?.current, state.sessions?.other.orEmpty(), state.sessionsError, vm)

            HorizontalDivider()
            ViewSection(state.user?.view, busy = state.busy, onSave = vm::saveView)
        }
    }
}

@Composable
private fun UsernameSection(current: String?, busy: Boolean, onSave: (String) -> Unit) {
    var value by remember(current) { mutableStateOf(current.orEmpty()) }
    Text("Username", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(
        value = value,
        onValueChange = { value = it },
        label = { Text("Username") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        enabled = !busy && value.isNotBlank() && value != current,
        onClick = { onSave(value) },
    ) { Text("Change username") }
}

@Composable
private fun PasswordSection(busy: Boolean, onSave: (String, String) -> Unit) {
    var currentPw by remember { mutableStateOf("") }
    var newPw by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val mismatch = confirm.isNotEmpty() && confirm != newPw

    Text("Password", style = MaterialTheme.typography.titleMedium)
    Text(
        "Changing your password signs out every other device. This one keeps working, because " +
            "it uses an API token rather than a session.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = currentPw,
        onValueChange = { currentPw = it },
        label = { Text("Current password") },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = newPw,
        onValueChange = { newPw = it },
        label = { Text("New password") },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = confirm,
        onValueChange = { confirm = it },
        label = { Text("Repeat new password") },
        isError = mismatch,
        supportingText = { if (mismatch) Text("The two entries do not match.") },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        enabled = !busy && currentPw.isNotBlank() && newPw.isNotBlank() && newPw == confirm,
        onClick = {
            onSave(currentPw, newPw)
            currentPw = ""; newPw = ""; confirm = ""
        },
    ) { Text("Change password") }
}

@Composable
private fun SessionsSection(
    current: ZSession?,
    others: List<ZSession>,
    error: String?,
    vm: AccountViewModel,
) {
    var confirmAll by remember { mutableStateOf(false) }

    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text("Sign out all other devices?") },
            text = {
                Text(
                    "${others.size} other session(s) end immediately. This device stays signed " +
                        "in. Anyone using those sessions has to log in again.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmAll = false; vm.logOutOtherSessions() }) {
                    Text("Sign them out", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text("Cancel") } },
        )
    }

    Text("Logged-in devices", style = MaterialTheme.typography.titleMedium)
    when {
        error != null -> Text(
            error,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )

        else -> {
            current?.let { SessionRow(it, onLogOut = null) }
            if (others.isEmpty()) {
                Text(
                    "No other devices are signed in.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                others.forEach { s -> SessionRow(s) { vm.logOutSession(s.id) } }
                OutlinedButton(onClick = { confirmAll = true }) {
                    Icon(
                        Icons.AutoMirrored.Filled.Logout,
                        null,
                        Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        "  Sign out all other devices",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/** A null [onLogOut] is what marks the session you are using: the server refuses to end it. */
@Composable
private fun SessionRow(session: ZSession, onLogOut: (() -> Unit)?) {
    OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    session.device?.takeIf { it.isNotBlank() } ?: "Unknown device",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    buildString {
                        session.client?.takeIf { it.isNotBlank() }?.let { append(it) }
                        session.createdAt?.let {
                            if (isNotEmpty()) append(" - ")
                            append("signed in ").append(it.take(10))
                        }
                    }.ifBlank { "No details reported" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (onLogOut == null) {
                Text(
                    "This device",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                TextButton(onClick = onLogOut) {
                    Text("Sign out", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/**
 * Zipline's "Viewing Files" panel. These change the public `/view/<id>` page and the OpenGraph
 * tags chat apps read - nothing about them affects this app's own display.
 */
@Composable
private fun ViewSection(view: ZView?, busy: Boolean, onSave: (ZView) -> Unit) {
    var draft by remember(view) { mutableStateOf(view ?: ZView()) }

    Text("Viewing files", style = MaterialTheme.typography.titleMedium)
    Text(
        "Controls the public view page for your uploads and the link preview other apps show. " +
            "Text fields accept Zipline's {variables}.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    ViewToggle("Enable view routes", draft.enabled) { draft = draft.copy(enabled = it) }
    ViewToggle("Disable text files", draft.disableTextFiles) {
        draft = draft.copy(disableTextFiles = it)
    }
    ViewToggle("Show mimetype", draft.showMimetype) { draft = draft.copy(showMimetype = it) }
    ViewToggle("Show tags", draft.showTags) { draft = draft.copy(showTags = it) }
    ViewToggle("Show folder", draft.showFolder) { draft = draft.copy(showFolder = it) }

    OutlinedTextField(
        value = draft.content.orEmpty(),
        onValueChange = { draft = draft.copy(content = it) },
        label = { Text("View content") },
        supportingText = { Text("HTML is allowed; JavaScript is not.") },
        minLines = 3,
        modifier = Modifier.fillMaxWidth(),
    )

    Text("Content alignment", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("left", "center", "right").forEach { option ->
            FilterChip(
                selected = (draft.align ?: "left") == option,
                onClick = { draft = draft.copy(align = option) },
                label = { Text(option.replaceFirstChar(Char::uppercase)) },
            )
        }
    }

    // Media-only is Zipline's fallback for when embeds are OFF - it adds just the image/video
    // tags so chat apps still unfurl the media. With embeds on it is meaningless, so it is forced
    // off and locked rather than left as a setting that quietly does nothing.
    ViewToggle("Enable embed", draft.embed) {
        draft = draft.copy(embed = it, embedMediaOnly = if (it) false else draft.embedMediaOnly)
    }
    ViewToggle(
        "Media-only link preview",
        draft.embedMediaOnly,
        enabled = draft.embed != true,
    ) { draft = draft.copy(embedMediaOnly = it) }
    if (draft.embed == true) {
        Text(
            "Only applies while embeds are off - the embed already carries the media.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 52.dp),
        )
    }
    ViewField("Embed title", draft.embedTitle) { draft = draft.copy(embedTitle = it) }
    ViewField("Embed description", draft.embedDescription) {
        draft = draft.copy(embedDescription = it)
    }
    ViewField("Embed site name", draft.embedSiteName) { draft = draft.copy(embedSiteName = it) }
    ViewField("Embed colour (e.g. #4f46e5)", draft.embedColor) {
        draft = draft.copy(embedColor = it)
    }

    Button(enabled = !busy && draft != (view ?: ZView()), onClick = { onSave(draft) }) {
        Text("Save view settings")
    }
}

@Composable
private fun ViewToggle(
    label: String,
    checked: Boolean?,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = checked == true, onCheckedChange = onChange, enabled = enabled)
        Text(
            label,
            Modifier.padding(start = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun ViewField(label: String, value: String?, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value.orEmpty(),
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun BigAvatar(avatar: String?, username: String?) {
    val bitmap = rememberDataUrlBitmap(avatar)
    Box(
        Modifier.size(72.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Avatar",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(72.dp).clip(CircleShape),
            )
        } else {
            Text(
                username?.firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}
