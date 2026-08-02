package dev.zipshare.ui.upload

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zipshare.data.model.formatLink
import dev.zipshare.ui.shell.LocalLinkFormat
import dev.zipshare.ui.shell.ShellTopBar

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UploadTextScreen(onMenu: () -> Unit, vm: UploadTextViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    val linkFormat = LocalLinkFormat.current

    LaunchedEffect(state.error) { state.error?.let { snackbar.showSnackbar(it); vm.clearError() } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            ShellTopBar(
                title = "Upload text",
                profiles = state.profiles,
                activeLabel = state.active?.label,
                onMenu = onMenu,
                onSelectProfile = vm::selectProfile,
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.uploading) LinearProgressIndicator(Modifier.fillMaxWidth())

            OutlinedTextField(
                value = state.text,
                onValueChange = vm::setText,
                label = { Text("Text") },
                placeholder = { Text("Paste or type here...") },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp),
            )

            Text("Language", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UploadTextViewModel.LANGUAGES.forEach { lang ->
                    FilterChip(
                        selected = state.extension == lang.ext,
                        onClick = { vm.setLanguage(lang) },
                        label = { Text(lang.ext) },
                    )
                }
            }

            Text(
                "Uploads as text.${state.extension} (${state.mime}), ${state.byteCount} bytes",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(
                onClick = vm::upload,
                enabled = state.text.isNotBlank() && !state.uploading && state.active != null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (state.uploading) "Uploading..." else "Upload") }

            state.resultUrl?.let { url ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Uploaded",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(url, style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(
                            onClick = {
                                clipboard.setText(
                                    AnnotatedString(
                                        formatLink(url.substringAfterLast('/'), url, linkFormat),
                                    ),
                                )
                            },
                        ) {
                            Icon(Icons.Filled.ContentCopy, "Copy link")
                        }
                    }
                }
            }
        }
    }
}
