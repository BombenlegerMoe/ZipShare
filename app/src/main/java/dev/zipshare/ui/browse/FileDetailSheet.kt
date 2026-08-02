package dev.zipshare.ui.browse

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.zipshare.data.net.ZFile
import dev.zipshare.data.net.ZFolder
import dev.zipshare.data.net.ZTag
import dev.zipshare.data.net.hasPassword
import dev.zipshare.data.net.hasVisualPreview
import dev.zipshare.data.net.previewUrl
import dev.zipshare.data.net.shareUrl
import dev.zipshare.ui.home.humanSize
import dev.zipshare.ui.home.iconFor
import java.text.DateFormat
import java.util.Date

/**
 * The file detail view, mirroring what Zipline's web dashboard shows when you open a file:
 * preview, timestamps, size/type/views, tags, and the actions - favourite, edit, move, delete.
 */
@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalLayoutApi::class,
    ExperimentalFoundationApi::class,
)
@Composable
fun FileDetailSheet(
    file: ZFile,
    baseUrl: String,
    allTags: List<ZTag>,
    folders: List<ZFolder>,
    onDismiss: () -> Unit,
    onToggleFavourite: () -> Unit,
    onPatch: (name: String?, maxViews: Int?, tags: List<String>?) -> Unit,
    onMove: (folderId: String) -> Unit,
    onCreateTag: (name: String, color: String) -> Unit,
    onEditTag: (id: String, name: String, color: String) -> Unit,
    onDeleteTag: (id: String) -> Unit,
    onSetPassword: (password: String?) -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var editing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var newTag by remember { mutableStateOf(false) }
    var editTag by remember { mutableStateOf<ZTag?>(null) }
    var managingTags by remember { mutableStateOf(false) }
    var editingPassword by remember { mutableStateOf(false) }
    var password by remember(file.id) { mutableStateOf("") }

    var name by remember(file.id) { mutableStateOf(file.name) }
    var maxViews by remember(file.id) { mutableStateOf(file.maxViews?.toString().orEmpty()) }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this file?") },
            text = { Text("${file.name} is removed from the server permanently.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
    if (moving) {
        FolderPickDialog(
            folders = folders,
            onDismiss = { moving = false },
            onPick = { moving = false; onMove(it) },
        )
    }
    if (newTag) {
        TagDialog(onDismiss = { newTag = false }, onSave = { n, c -> onCreateTag(n, c) })
    }
    editTag?.let { tag ->
        TagDialog(
            existing = tag,
            onDismiss = { editTag = null },
            onSave = { n, c -> onEditTag(tag.id, n, c) },
            onDelete = { onDeleteTag(tag.id) },
        )
    }
    if (managingTags) {
        AlertDialog(
            onDismissRequest = { managingTags = false },
            title = { Text("Edit tags") },
            text = {
                Column {
                    Text(
                        "Pick a tag to rename, recolour or delete.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    allTags.forEach { tag ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { managingTags = false; editTag = tag }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .size(14.dp)
                                    .background(parseColour(tag.color), RoundedCornerShape(7.dp)),
                            )
                            Text(tag.name, Modifier.padding(start = 10.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { managingTags = false }) { Text("Close") } },
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHighest,
                        RoundedCornerShape(12.dp),
                    )
                    .clickable(onClick = onOpen),
                contentAlignment = Alignment.Center,
            ) {
                if (file.hasVisualPreview()) {
                    AsyncImage(
                        model = file.previewUrl(baseUrl),
                        contentDescription = file.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().height(180.dp),
                    )
                } else {
                    Icon(iconFor(file.type), null, Modifier.size(56.dp))
                }
            }

            Text(
                file.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            // --- actions ---
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onToggleFavourite) {
                    Icon(
                        if (file.favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                        null,
                        Modifier.size(18.dp),
                    )
                    Text(
                        if (file.favorite) " Favourited" else " Favourite",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                OutlinedButton(onClick = { moving = true }, enabled = folders.isNotEmpty()) {
                    Icon(Icons.Filled.DriveFileMove, null, Modifier.size(18.dp))
                    Text(" Move", style = MaterialTheme.typography.labelLarge)
                }
                OutlinedButton(onClick = { confirmDelete = true }) {
                    Icon(
                        Icons.Filled.Delete,
                        null,
                        Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }

            HorizontalDivider()

            // --- info ---
            InfoRow("Type", file.type)
            InfoRow("Size", humanSize(file.size))
            InfoRow("Views", buildString {
                append(file.views)
                file.maxViews?.let { append(" of $it max") }
            })
            file.originalName?.let { InfoRow("Original name", it) }
            InfoRow("Uploaded", formatStamp(file.createdAt))
            file.updatedAt?.let { InfoRow("Last modified", formatStamp(it)) }
            file.deletesAt?.let { InfoRow("Expires", formatStamp(it)) }
            InfoRow("Folder", folders.firstOrNull { it.id == file.folderId }?.name ?: "None")
            InfoRow("Link", file.shareUrl(baseUrl))

            HorizontalDivider()

            // --- tags ---
            Text("Tags", style = MaterialTheme.typography.titleSmall)
            if (allTags.isEmpty()) {
                Text(
                    "No tags on this server yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val assigned = file.tags.map { it.id }.toSet()
                allTags.forEach { tag ->
                    FilterChip(
                        selected = tag.id in assigned,
                        onClick = {
                            val next = if (tag.id in assigned) assigned - tag.id else assigned + tag.id
                            onPatch(null, null, next.toList())
                        },
                        label = { Text(tag.name) },
                        leadingIcon = {
                            Box(
                                Modifier
                                    .size(12.dp)
                                    .background(parseColour(tag.color), RoundedCornerShape(6.dp)),
                            )
                        },
                    )
                }
                AssistChip(
                    onClick = { newTag = true },
                    label = { Text("New tag") },
                    colors = AssistChipDefaults.assistChipColors(),
                )
                // A chip owns its own gestures, so long-press on one never reaches us - editing
                // needs its own entry point rather than a hidden gesture.
                if (allTags.isNotEmpty()) {
                    AssistChip(onClick = { managingTags = true }, label = { Text("Edit tags") })
                }
            }

            HorizontalDivider()

            // --- password ---
            Text("Password", style = MaterialTheme.typography.titleSmall)
            if (!editingPassword) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (file.hasPassword()) "Protected" else "Not protected",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { editingPassword = true }) {
                            Text(if (file.hasPassword()) "Change" else "Set")
                        }
                        if (file.hasPassword()) {
                            TextButton(onClick = { onSetPassword(null) }) { Text("Remove") }
                        }
                    }
                }
            } else {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("New password") },
                    supportingText = { Text("Viewers must enter this before the file opens.") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = password.isNotBlank(),
                        onClick = {
                            onSetPassword(password)
                            password = ""
                            editingPassword = false
                        },
                    ) { Text("Save") }
                    TextButton(onClick = { password = ""; editingPassword = false }) { Text("Cancel") }
                }
            }

            HorizontalDivider()

            // --- edit ---
            if (!editing) {
                OutlinedButton(onClick = { editing = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Edit name and view limit")
                }
            } else {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = maxViews,
                    onValueChange = { maxViews = it.filter(Char::isDigit) },
                    label = { Text("Max views") },
                    supportingText = { Text("Empty means unlimited.") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            onPatch(
                                name.trim().takeIf { it.isNotBlank() && it != file.name },
                                maxViews.toIntOrNull(),
                                null,
                            )
                            editing = false
                        },
                    ) { Text("Save") }
                    TextButton(
                        onClick = {
                            name = file.name
                            maxViews = file.maxViews?.toString().orEmpty()
                            editing = false
                        },
                    ) { Text("Cancel") }
                }
            }

            OutlinedButton(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.OpenInNew, null, Modifier.size(18.dp))
                Text(" Open full screen")
            }
            Box(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(0.38f),
        )
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
    }
}

/** Creates a tag, or edits/deletes one when [existing] is supplied. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagDialog(
    existing: ZTag? = null,
    onDismiss: () -> Unit,
    onSave: (name: String, colour: String) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var colour by remember { mutableStateOf(existing?.color ?: PRESET_COLOURS.first()) }
    var confirmDelete by remember { mutableStateOf(false) }

    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete tag?") },
            text = {
                Text("\"${existing?.name}\" is removed from every file that uses it. The files stay.")
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete(); onDismiss() }) {
                    Text("Delete")
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New tag" else "Edit tag") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                )
                Text("Colour", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (PRESET_COLOURS + listOfNotNull(existing?.color?.takeIf { it !in PRESET_COLOURS }))
                        .forEach { hex ->
                            Box(
                                Modifier
                                    .size(32.dp)
                                    .background(parseColour(hex), RoundedCornerShape(16.dp))
                                    .clickable { colour = hex },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (colour == hex) {
                                    Icon(
                                        Icons.Filled.Star,
                                        null,
                                        Modifier.size(14.dp),
                                        tint = Color.White,
                                    )
                                }
                            }
                        }
                }
                if (onDelete != null) {
                    TextButton(onClick = { confirmDelete = true }) {
                        Text("Delete this tag", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onSave(name.trim(), colour); onDismiss() },
            ) { Text(if (existing == null) "Create" else "Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private val PRESET_COLOURS =
    listOf("#E85D75", "#5DADE8", "#F7BE54", "#7ED991", "#A882E8", "#54D4D4")

/** Tag colours arrive as #rgb or #rrggbb; fall back to grey rather than throwing on junk. */
internal fun parseColour(hex: String): Color = runCatching {
    val cleaned = hex.removePrefix("#")
    val full = if (cleaned.length == 3) cleaned.map { "$it$it" }.joinToString("") else cleaned
    Color(("ff$full").toLong(16))
}.getOrDefault(Color(0xFF888888))

/** ISO-8601 from the server; show it in the device's own format, or verbatim if unparseable. */
internal fun formatStamp(raw: String?): String {
    if (raw.isNullOrBlank()) return "unknown"
    return runCatching {
        val instant = java.time.Instant.parse(raw)
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(instant.toEpochMilli()))
    }.getOrDefault(raw)
}
