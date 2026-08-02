package dev.zipshare.upload

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat

/** Notification "Copy" action. A receiver, not an activity trampoline (blocked on Android 12+). */
class ClipboardReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val url = intent.getStringExtra(EXTRA_URL) ?: return

        UploadNotifications(context.applicationContext).copyToClipboard(url)

        // Android 13+ shows its own clipboard confirmation.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
        }
        intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1).takeIf { it >= 0 }?.let {
            NotificationManagerCompat.from(context).cancel(it)
        }
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_NOTIFICATION_ID = "nid"
    }
}
