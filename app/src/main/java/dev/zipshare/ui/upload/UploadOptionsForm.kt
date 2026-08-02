package dev.zipshare.ui.upload

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.zipshare.data.model.CompressionType
import dev.zipshare.data.model.NameFormat
import dev.zipshare.data.model.UploadOptions
import dev.zipshare.data.net.ZFolder

/**
 * The upload-option controls, shared by the per-upload sheet and the saved defaults in Settings,
 * so the two can never drift apart.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UploadOptionsForm(
    options: UploadOptions,
    folders: List<ZFolder>,
    onChange: (UploadOptions) -> Unit,
    modifier: Modifier = Modifier,
    /** Hidden when several files are selected: one name cannot apply to all of them. */
    showFilenameField: Boolean = true,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Expires", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            UploadOptions.EXPIRY_PRESETS.forEach { preset ->
                FilterChip(
                    selected = options.deletesAt == preset,
                    onClick = {
                        onChange(
                            options.copy(deletesAt = if (options.deletesAt == preset) null else preset),
                        )
                    },
                    label = { Text(preset) },
                )
            }
        }

        Text("Name format", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NameFormat.entries.forEach { format ->
                FilterChip(
                    selected = options.format == format,
                    onClick = {
                        onChange(options.copy(format = if (options.format == format) null else format))
                    },
                    label = { Text(format.wire) },
                )
            }
        }

        if (folders.isNotEmpty()) {
            Text("Folder", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                folders.forEach { folder ->
                    FilterChip(
                        selected = options.folderId == folder.id,
                        onClick = {
                            onChange(
                                options.copy(
                                    folderId = if (options.folderId == folder.id) null else folder.id,
                                ),
                            )
                        },
                        label = { Text(folder.name) },
                    )
                }
            }
        }

        Text("Image compression", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CompressionType.entries.forEach { type ->
                FilterChip(
                    selected = options.compressionType == type,
                    onClick = {
                        onChange(
                            options.copy(
                                compressionType = if (options.compressionType == type) null else type,
                            ),
                        )
                    },
                    label = { Text(if (type == CompressionType.AUTO) "auto" else type.wire) },
                )
            }
        }
        if (options.compressionType == CompressionType.AUTO) {
            Text(
                "Each image is re-encoded to the format it already is. Files Zipline cannot " +
                    "re-encode - anything that is not JPEG, PNG, WebP or JXL - upload untouched " +
                    "instead of being converted.\n\n" +
                    "The quality is per format because it does not mean the same thing in each: " +
                    "for JPEG it is a real lossy quality, while PNG is lossless and barely " +
                    "shrinks at the same number. Leave one blank to skip compressing that format.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = options.autoPercentJpg?.toString().orEmpty(),
                onValueChange = { v ->
                    onChange(options.copy(autoPercentJpg = v.toIntOrNull()?.coerceIn(0, 100)))
                },
                label = { Text("JPEG quality (0-100)") },
                supportingText = { Text("Also used for WebP and JXL, which are lossy too.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = options.autoPercentPng?.toString().orEmpty(),
                onValueChange = { v ->
                    onChange(options.copy(autoPercentPng = v.toIntOrNull()?.coerceIn(0, 100)))
                },
                label = { Text("PNG quality (0-100)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            OutlinedTextField(
                value = options.compressionPercent?.toString().orEmpty(),
                onValueChange = { v ->
                    onChange(options.copy(compressionPercent = v.toIntOrNull()?.coerceIn(0, 100)))
                },
                label = { Text("Compression percent (0-100)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        OutlinedTextField(
            value = options.password.orEmpty(),
            onValueChange = { onChange(options.copy(password = it.ifBlank { null })) },
            label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = options.maxViews?.toString().orEmpty(),
            onValueChange = { v ->
                onChange(options.copy(maxViews = v.toIntOrNull()?.coerceAtLeast(0)))
            },
            label = { Text("Max views") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        if (showFilenameField) {
            OutlinedTextField(
                value = options.filename.orEmpty(),
                onValueChange = { onChange(options.copy(filename = it.ifBlank { null })) },
                label = { Text("Filename override (beats name format)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        OutlinedTextField(
            value = options.fileExtension.orEmpty(),
            onValueChange = { onChange(options.copy(fileExtension = it.ifBlank { null })) },
            label = { Text("File extension override") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = options.domain.orEmpty(),
            onValueChange = { onChange(options.copy(domain = it.ifBlank { null })) },
            label = { Text("Return domain(s), comma separated") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = options.originalName,
                onCheckedChange = { onChange(options.copy(originalName = it)) },
            )
            Text("Keep original name", Modifier.padding(start = 8.dp))
        }
    }
}
