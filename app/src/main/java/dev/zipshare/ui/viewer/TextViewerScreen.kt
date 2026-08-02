package dev.zipshare.ui.viewer

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * In-app viewer and editor for text uploads.
 *
 * Read mode wraps nothing and scrolls both ways, so code and logs keep their shape. Editing is
 * real, but Zipline exposes no way to replace a file's bytes, so saving uploads a new file - the
 * UI states that plainly instead of implying the original was overwritten.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextViewerScreen(
    name: String,
    rawUrl: String,
    mime: String,
    onBack: () -> Unit,
    vm: TextViewerViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    var editing by remember { mutableStateOf(false) }
    var saveDialog by remember { mutableStateOf(false) }

    LaunchedEffect(rawUrl) { vm.load(rawUrl) }
    LaunchedEffect(state.error) {
        state.error?.let { snackbar.showSnackbar(it); vm.clearError() }
    }
    LaunchedEffect(state.savedUrl) {
        state.savedUrl?.let { snackbar.showSnackbar("Saved as a new file") }
    }

    if (saveDialog) {
        SaveAsDialog(
            suggested = suggestName(name),
            onDismiss = { saveDialog = false },
            onSave = { newName ->
                saveDialog = false
                vm.saveAsNew(newName, mime)
            },
        )
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
                title = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    IconButton(
                        onClick = { clipboard.setText(AnnotatedString(state.text)) },
                        enabled = !state.loading,
                    ) { Icon(Icons.Filled.ContentCopy, "Copy contents") }

                    if (state.dirty) {
                        IconButton(onClick = vm::revert) { Icon(Icons.Filled.Undo, "Revert changes") }
                    }
                    IconButton(onClick = { editing = !editing }, enabled = !state.loading) {
                        Icon(
                            if (editing) Icons.Filled.Visibility else Icons.Filled.Edit,
                            if (editing) "Read mode" else "Edit",
                        )
                    }
                    IconButton(
                        onClick = { saveDialog = true },
                        enabled = state.dirty && !state.saving,
                    ) { Icon(Icons.Filled.Save, "Save as new file") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                state.tooLarge -> Text(
                    "This file is too large to open in the editor. Open it in a browser instead.",
                    Modifier.align(Alignment.Center).padding(24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )

                editing -> Column(Modifier.fillMaxSize()) {
                    Text(
                        "Zipline cannot replace a file's contents, so saving creates a new file.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                    OutlinedTextField(
                        value = state.text,
                        onValueChange = vm::setText,
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }

                else -> SelectionContainer {
                    // No wrapping: code and logs stay aligned, and the row scrolls sideways.
                    Text(
                        state.text,
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                        softWrap = false,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState())
                            .padding(16.dp),
                    )
                }
            }

            if (state.saving) {
                CircularProgressIndicator(Modifier.align(Alignment.Center).size(36.dp))
            }
        }
    }
}

@Composable
private fun SaveAsDialog(suggested: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(suggested) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save as new file") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "The original stays as it is - Zipline has no way to overwrite a file's " +
                        "contents, so this uploads the edited text alongside it.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("File name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim()) }) {
                Text("Upload")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** "notes.md" -> "notes-edited.md", keeping the extension so the type is preserved. */
internal fun suggestName(original: String): String {
    val dot = original.lastIndexOf('.')
    return if (dot <= 0) "$original-edited" else {
        original.substring(0, dot) + "-edited" + original.substring(dot)
    }
}
