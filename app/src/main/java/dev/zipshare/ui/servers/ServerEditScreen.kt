package dev.zipshare.ui.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zipshare.data.net.CertRole
import dev.zipshare.ui.SecureScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerEditScreen(
    profileId: String?,
    onDone: () -> Unit,
    vm: ServersViewModel = hiltViewModel(),
) {
    // The token and the pin are on screen here.
    SecureScreen()

    LaunchedEffect(profileId) { vm.load(profileId) }
    val state by vm.edit.collectAsStateWithLifecycle()
    val p = state.profile

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (profileId == null) "Add server" else "Edit server") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = p.label,
                onValueChange = { vm.update(p.copy(label = it)) },
                label = { Text("Label") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = p.baseUrl,
                onValueChange = { vm.update(p.copy(baseUrl = it)) },
                label = { Text("Base URL") },
                placeholder = { Text("https://zipline.example.com") },
                isError = state.urlError != null,
                supportingText = { state.urlError?.let { Text(it) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Sign in with", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.authMode == AuthMode.TOKEN,
                    onClick = { vm.setAuthMode(AuthMode.TOKEN) },
                    label = { Text("API token") },
                )
                FilterChip(
                    selected = state.authMode == AuthMode.PASSWORD,
                    onClick = { vm.setAuthMode(AuthMode.PASSWORD) },
                    label = { Text("Username") },
                )
            }

            when (state.authMode) {
                AuthMode.TOKEN -> OutlinedTextField(
                    value = p.token,
                    onValueChange = { vm.update(p.copy(token = it.trim())) },
                    label = { Text("Token") },
                    supportingText = { Text("From your Zipline user settings.") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                AuthMode.PASSWORD -> {
                    OutlinedTextField(
                        value = state.username,
                        onValueChange = vm::setUsername,
                        label = { Text("Username") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.password,
                        onValueChange = vm::setPassword,
                        label = { Text("Password") },
                        supportingText = {
                            Text("Used once to fetch your API token, then discarded - never stored.")
                        },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (state.totpRequired) {
                        OutlinedTextField(
                            value = state.totpCode,
                            onValueChange = vm::setTotpCode,
                            label = { Text("Two-factor code") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    state.signInError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Button(
                        onClick = vm::signIn,
                        enabled = !state.signingIn,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (state.signingIn) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text(if (state.totpRequired) "Verify code" else "Sign in")
                        }
                    }
                    if (p.token.isNotBlank()) {
                        Text(
                            "Token retrieved. Press Save to keep this server.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                // Signing up from an invite is a first-run thing and lives on the welcome screen;
                // this editor is for servers you already have an account on. Unreachable here
                // because no chip above selects it.
                AuthMode.REGISTER -> Unit
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = p.allowCleartext,
                    onCheckedChange = { vm.update(p.copy(allowCleartext = it)) },
                )
                Column(Modifier.padding(start = 8.dp)) {
                    Text("Allow cleartext (http://)")
                    Text(
                        "The host must also be listed in network_security_config.xml.",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }

            Text("Certificate pinning", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = p.pinnedSpkiSha256.orEmpty(),
                onValueChange = {
                    vm.update(p.copy(pinnedSpkiSha256 = it.trim().ifBlank { null }))
                },
                label = { Text("SPKI SHA-256 pin(s)") },
                placeholder = { Text("sha256/AAAA...=  (comma-separate for a backup pin)") },
                supportingText = {
                    Text("Leave empty for normal certificate validation. Several pins may be given.")
                },
                singleLine = false,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = vm::fetchPins) { Text("Fetch current pin") }
            state.chainError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.chain.forEach { link ->
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            link.role.name.lowercase().replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (link.recommended) {
                            Text(
                                "  - recommended",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Text(link.subject, style = MaterialTheme.typography.labelMedium, maxLines = 2)
                    Text("expires ${link.notAfter}", style = MaterialTheme.typography.labelSmall)
                    Text(link.pin, style = MaterialTheme.typography.bodySmall)
                    when (link.role) {
                        CertRole.LEAF -> Text(
                            "Renews often (ACME/Cloudflare rotate this). Pinning it will break the " +
                                "app at the next renewal.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )

                        CertRole.ROOT -> Text(
                            "Long-lived, but pinning a public root gives you little over normal " +
                                "certificate validation.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        CertRole.INTERMEDIATE -> Text(
                            "Stable for years and survives leaf renewals - the safe choice.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (link.recommended) {
                        Button(onClick = { vm.update(p.copy(pinnedSpkiSha256 = link.pin)) }) {
                            Text("Pin this (recommended)")
                        }
                    } else {
                        TextButton(onClick = { vm.update(p.copy(pinnedSpkiSha256 = link.pin)) }) {
                            Text("Pin this anyway")
                        }
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = vm::test, enabled = !state.testing) {
                    Text("Test connection")
                }
                if (state.testing) CircularProgressIndicator(Modifier.size(24.dp))
            }
            state.result?.let { r ->
                Text(
                    r.message,
                    color = if (r.ok) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }

            Button(
                onClick = { vm.save(onDone) },
                enabled = p.token.isNotBlank() && p.baseUrl.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }
        }
    }
}
