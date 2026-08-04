package dev.zipshare.ui.welcome

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zipshare.ui.SecureScreen
import dev.zipshare.ui.servers.AuthMode
import dev.zipshare.ui.servers.ServersViewModel

/**
 * First-run sign-in. Shown instead of the app shell while no server profile exists, so every way
 * to connect - including signing up from an invite - is the first thing you see rather than
 * something to find in Settings.
 *
 * Deliberately backed by [ServersViewModel] - the same login, QR and validation logic as the
 * server editor, not a second copy of it.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun WelcomeScreen(vm: ServersViewModel = hiltViewModel()) {
    // A token is on screen here.
    SecureScreen()

    val state by vm.edit.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val p = state.profile
    var advanced by remember { mutableStateOf(false) }

    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(16.dp))

            // Persistent rather than a snackbar: this explains why the user's servers vanished,
            // and a message that disappears after four seconds is how that turns into a bug report.
            notice?.let { text ->
                OutlinedCard(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.outlinedCardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "Saved servers were cleared",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        TextButton(onClick = vm::clearNotice, modifier = Modifier.align(Alignment.End)) {
                            Text("Dismiss")
                        }
                    }
                }
            }

            Text(
                "Welcome to ZipShare",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            Text(
                "Connect to your self-hosted Zipline server to get started.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = p.baseUrl,
                onValueChange = { vm.update(p.copy(baseUrl = it)) },
                label = { Text("Server address") },
                placeholder = { Text("https://zipline.example.com") },
                isError = state.urlError != null,
                supportingText = { state.urlError?.let { Text(it) } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )

            Text("How would you like to sign in?", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeChip(
                    selected = state.authMode == AuthMode.PASSWORD,
                    label = "Username",
                    icon = { Icon(Icons.Filled.Person, null, Modifier.size(18.dp)) },
                ) { vm.setAuthMode(AuthMode.PASSWORD) }
                ModeChip(
                    selected = state.authMode == AuthMode.TOKEN,
                    label = "Token",
                    icon = { Icon(Icons.Filled.Key, null, Modifier.size(18.dp)) },
                ) { vm.setAuthMode(AuthMode.TOKEN) }
                ModeChip(
                    selected = state.authMode == AuthMode.REGISTER,
                    label = "Invite",
                    icon = { Icon(Icons.Filled.PersonAdd, null, Modifier.size(18.dp)) },
                ) { vm.setAuthMode(AuthMode.REGISTER) }
            }

            when (state.authMode) {
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
                        supportingText = { Text("Fetches your API token once, then is discarded.") },
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
                    Button(
                        // One tap: signing in fetches the token and saves the profile, which is
                        // what makes this screen go away. Nothing is left to confirm.
                        onClick = { vm.signIn(autoConnect = true) },
                        enabled = !state.signingIn,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (state.signingIn) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text(if (state.totpRequired) "Verify code" else "Sign in")
                        }
                    }
                }

                AuthMode.TOKEN -> {
                    OutlinedTextField(
                        value = p.token,
                        onValueChange = { vm.update(p.copy(token = it.trim())) },
                        label = { Text("API token") },
                        supportingText = { Text("Copy it from your Zipline user settings.") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                AuthMode.REGISTER -> {
                    OutlinedTextField(
                        value = state.inviteLink,
                        onValueChange = vm::setInviteLink,
                        label = { Text("Invite link or code") },
                        placeholder = { Text("https://zipline.example.com/invite/abc123") },
                        supportingText = {
                            Text(
                                if (state.inviteCode != null) {
                                    "Invite code: ${state.inviteCode}"
                                } else {
                                    "Paste the link you were sent - it fills in the server too."
                                },
                            )
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.username,
                        onValueChange = vm::setUsername,
                        label = { Text("Choose a username") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.password,
                        onValueChange = vm::setPassword,
                        label = { Text("Choose a password") },
                        supportingText = { Text("Used once to create the account, then discarded.") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = vm::register,
                        enabled = !state.signingIn,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (state.signingIn) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Create account")
                        }
                    }
                }
            }

            state.signInError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            state.result?.let {
                Text(
                    it.message,
                    color = if (it.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // Only the token flow needs a confirm button - the other two connect themselves as
            // soon as the server hands over a token.
            if (state.authMode == AuthMode.TOKEN) {
                Button(
                    onClick = { vm.save {} },
                    enabled = p.baseUrl.isNotBlank() && p.token.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Connect") }
            }

            TextButton(onClick = { advanced = !advanced }, modifier = Modifier.fillMaxWidth()) {
                Text(if (advanced) "Hide advanced options" else "Advanced options")
            }
            AnimatedVisibility(visible = advanced) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = p.label,
                        onValueChange = { vm.update(p.copy(label = it)) },
                        label = { Text("Label (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = p.allowCleartext,
                            onCheckedChange = { vm.update(p.copy(allowCleartext = it)) },
                        )
                        Column(Modifier.padding(start = 8.dp)) {
                            Text("Allow cleartext (http://)")
                            Text(
                                "Only for a server without TLS, and it must be allow-listed in " +
                                    "network_security_config.xml.",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    OutlinedTextField(
                        value = p.pinnedSpkiSha256.orEmpty(),
                        onValueChange = {
                            vm.update(p.copy(pinnedSpkiSha256 = it.ifBlank { null }))
                        },
                        label = { Text("SPKI SHA-256 pin(s)") },
                        supportingText = { Text("Optional. Certificate pinning can also be set up later.") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(onClick = vm::test, modifier = Modifier.fillMaxWidth()) {
                        Text("Test connection")
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ModeChip(
    selected: Boolean,
    label: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = icon,
    )
}
