package dev.zipshare

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.zipshare.data.ProfileRepository
import dev.zipshare.data.prefs.SettingsStore
import dev.zipshare.log.AppLog
import dev.zipshare.upload.UploadEnqueuer
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Transparent share target: enqueues with the active profile's default upload options and finishes.
 * The uris are staged/persisted inside [UploadEnqueuer] before this task dies and the grant is lost.
 */
@AndroidEntryPoint
class ShareTargetActivity : ComponentActivity() {

    @Inject lateinit var profiles: ProfileRepository

    @Inject lateinit var enqueuer: UploadEnqueuer

    @Inject lateinit var settings: SettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uris = extractUris(intent)
        AppLog.log("share", "share received: ${uris.size} item(s)")
        if (uris.isEmpty()) {
            toastAndFinish("Nothing to upload.")
            return
        }
        lifecycleScope.launch {
            // Profiles load off the main thread, so wait for that before deciding there is none.
            profiles.awaitReady()
            val profile = profiles.activeNow()
            if (profile == null) {
                startActivity(
                    Intent(this@ShareTargetActivity, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                toastAndFinish("Add a server profile first.")
                return@launch
            }

            val options = settings.current().defaultOptions
            runCatching { enqueuer.enqueue(uris, profile.id, options) }
                .onSuccess { toastAndFinish("Uploading ${uris.size} file(s) to ${profile.label}") }
                .onFailure { toastAndFinish(it.message ?: "Could not start the upload.") }
        }
    }

    private fun extractUris(intent: Intent?): List<Uri> = when (intent?.action) {
        Intent.ACTION_SEND -> listOfNotNull(intent.parcelable<Uri>(Intent.EXTRA_STREAM))
        Intent.ACTION_SEND_MULTIPLE -> intent.parcelableList<Uri>(Intent.EXTRA_STREAM).orEmpty()
        else -> emptyList()
    }

    private fun toastAndFinish(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        finish()
    }

    @Suppress("DEPRECATION")
    private inline fun <reified T : Parcelable> Intent.parcelable(key: String): T? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(key, T::class.java)
        } else {
            getParcelableExtra(key) as? T
        }

    @Suppress("DEPRECATION")
    private inline fun <reified T : Parcelable> Intent.parcelableList(key: String): List<T>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableArrayListExtra(key, T::class.java)
        } else {
            getParcelableArrayListExtra<T>(key)
        }
}
