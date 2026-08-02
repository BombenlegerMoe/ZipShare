package dev.zipshare.ui.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import dev.zipshare.log.AppLog
import dev.zipshare.security.Biometrics
import dev.zipshare.ui.SecureScreen
import dev.zipshare.ui.shareFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun LockScreen(activity: FragmentActivity, onUnlocked: () -> Unit) {
    SecureScreen()

    var error by remember { mutableStateOf<String?>(null) }
    var prompting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun authenticate() {
        if (prompting) return
        prompting = true
        Biometrics.prompt(
            activity,
            onSuccess = {
                prompting = false
                error = null
                onUnlocked()
            },
            onError = {
                prompting = false
                error = it
            },
        )
    }

    LaunchedEffect(Unit) {
        // Nothing to authenticate against means nothing to lock behind.
        if (Biometrics.available(activity)) {
            authenticate()
        } else {
            AppLog.logAuth("unlocked without credential (none enrolled)")
            onUnlocked()
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("ZipShare is locked", style = MaterialTheme.typography.headlineSmall)
                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                Button(onClick = { authenticate() }, modifier = Modifier.padding(top = 24.dp)) {
                    Text("Unlock")
                }
            }

            // Deliberately available while locked: the login log records only lock/unlock events,
            // so a locked-out owner can still retrieve the evidence of attempted access.
            TextButton(
                onClick = {
                    scope.launch {
                        val file = withContext(Dispatchers.IO) {
                            val dir = File(activity.cacheDir, "export").apply { mkdirs() }
                            File(dir, "zipshare-login-log-${System.currentTimeMillis()}.txt")
                                .apply { writeText(AppLog.exportAuth()) }
                        }
                        shareFile(activity, file, "text/plain")
                    }
                },
                modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
            ) {
                Text("Export login log", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
