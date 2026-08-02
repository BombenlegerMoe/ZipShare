package dev.zipshare.ui.upload

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.zipshare.data.model.UploadOptions
import dev.zipshare.data.net.ZFolder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UploadSheet(
    fileCount: Int,
    folders: List<ZFolder>,
    options: UploadOptions,
    onOptionsChange: (UploadOptions) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    /** Restores the saved defaults, discarding tweaks made for this upload. */
    onResetToDefaults: () -> Unit,
    /** Names the files will upload under, so surprises surface here and not on the server. */
    fileNames: List<String> = emptyList(),
    /** True when the photo picker supplied numeric stand-in names instead of the real ones. */
    namesRedacted: Boolean = false,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Upload $fileCount file(s)", style = MaterialTheme.typography.titleLarge)
            if (fileNames.isNotEmpty()) {
                Text(
                    fileNames.take(3).joinToString("\n") +
                        if (fileNames.size > 3) "\n+${fileNames.size - 3} more" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (namesRedacted) {
                Text(
                    "Android's photo picker hides real file names for privacy - the app only " +
                        "gets these numbered stand-ins. Pick with the Files button (or set a " +
                        "filename below) when the original name matters.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            Text(
                "Starts from your saved defaults. Changes here apply to this upload only.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            UploadOptionsForm(
                options = options,
                folders = folders,
                onChange = onOptionsChange,
                showFilenameField = fileCount == 1,
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                AssistChip(onClick = onResetToDefaults, label = { Text("Reset") })
                Button(onClick = onConfirm, modifier = Modifier.weight(1f)) { Text("Upload") }
            }
        }
    }
}
