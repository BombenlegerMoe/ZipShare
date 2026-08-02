package dev.zipshare

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.zipshare.data.prefs.AppSettings
import dev.zipshare.data.prefs.SettingsStore
import dev.zipshare.security.AppLock
import dev.zipshare.ui.AppNav
import dev.zipshare.ui.lock.LockScreen
import dev.zipshare.ui.theme.ZipShareTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var appLock: AppLock

    @Inject lateinit var settingsStore: SettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        appLock.start()

        // Launcher shortcuts and the QS tile relaunch with CLEAR_TASK, so onCreate always sees
        // the action; no onNewIntent handling needed.
        val startAction = intent?.action
            ?.takeIf { it == ACTION_UPLOAD_FILE || it == ACTION_UPLOAD_TEXT }

        setContent {
            val settings by settingsStore.settings.collectAsStateWithLifecycle(AppSettings())
            val locked by appLock.locked.collectAsStateWithLifecycle()

            // Declared in the manifest but, on Android 13+, worthless until granted: every upload
            // notification is silently dropped otherwise. Asked once, on first launch.
            val notificationPermission = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { /* denial is fine - Settings explains how to turn them back on */ }

            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            ZipShareTheme(themeMode = settings.themeMode, dynamicColor = settings.dynamicColor) {
                if (locked && settings.appLockEnabled) {
                    LockScreen(activity = this, onUnlocked = appLock::unlock)
                } else {
                    AppNav(startAction = startAction)
                }
            }
        }
    }

    companion object {
        const val ACTION_UPLOAD_FILE = "dev.zipshare.action.UPLOAD_FILE"
        const val ACTION_UPLOAD_TEXT = "dev.zipshare.action.UPLOAD_TEXT"
    }
}
