package dev.zipshare.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zipshare.data.model.otpauthUri
import dev.zipshare.ui.SecureScreen
import dev.zipshare.ui.rememberDataUrlBitmap
import androidx.core.net.toUri

/**
 * Two-factor enrollment, mirroring the web dashboard's MFA panel.
 *
 * The QR is the server's own rendering - Zipline returns it ready-made as a data URL, so there is
 * nothing to encode here. The "Add to authenticator app" button exists because scanning your own
 * screen with the phone that is showing it is impossible: on one device the hand-off has to be an
 * `otpauth://` intent instead.
 */
@Composable
fun TotpSection(username: String?, vm: SettingsViewModel) {
    val state by vm.totp.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(Unit) { vm.loadTotp() }

    Text("Two-factor authentication", style = MaterialTheme.typography.titleMedium)

    when {
        state.loading -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)

        state.error != null && state.secret == null -> Text(
            state.error.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )

        state.enabled -> {
            Text(
                "Two-factor authentication is on for this account.",
                style = MaterialTheme.typography.bodySmall,
            )
            TotpCodeField(state.code, vm::setTotpCode, "Code from your authenticator")
            state.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            OutlinedButton(onClick = vm::disableTotp, enabled = !state.busy) {
                Text("Turn off two-factor")
            }
        }

        state.secret != null -> {
            // The secret and its QR are a credential: anyone who photographs this screen can
            // generate codes forever. Capture is blocked only while they are actually shown.
            SecureScreen()

            Text(
                "Add this account to an authenticator app, then enter the code it shows to " +
                    "switch two-factor on.",
                style = MaterialTheme.typography.bodySmall,
            )

            QrImage(state.qrcode)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    openInAuthenticator(context, otpauthUri(state.secret.orEmpty(), username.orEmpty()))
                }) { Text("Add to authenticator app") }
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(state.secret.orEmpty()))
                }) { Text("Copy secret") }
            }

            // The typed-in fallback, for an authenticator on a device that cannot see this screen.
            Text(
                state.secret.orEmpty().chunked(4).joinToString(" "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            TotpCodeField(state.code, vm::setTotpCode, "Six-digit code")
            state.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Button(onClick = vm::enableTotp, enabled = !state.busy) {
                Text("Turn on two-factor")
            }
        }
    }
}

@Composable
private fun TotpCodeField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(
            keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword,
        ),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** The QR arrives as `data:image/png;base64,...`, exactly like the account avatar does. */
@Composable
private fun QrImage(dataUrl: String?) {
    val bitmap = rememberDataUrlBitmap(dataUrl)
    if (bitmap != null) {
        // Forced white behind it: a QR inverted by dark theme will not scan.
        Column(
            Modifier.background(Color.White, RoundedCornerShape(8.dp)).padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Two-factor QR code",
                modifier = Modifier.size(180.dp),
            )
        }
    }
}

/**
 * Authenticator apps register the `otpauth` scheme, so ACTION_VIEW hands the account straight to
 * whichever one is installed. If none is, the intent has no target - say so rather than crashing,
 * since the secret is on screen to type in anyway.
 */
private fun openInAuthenticator(context: Context, uri: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri.toUri()))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(
            context,
            "No authenticator app installed - copy the secret instead.",
            Toast.LENGTH_LONG,
        ).show()
    }
}
