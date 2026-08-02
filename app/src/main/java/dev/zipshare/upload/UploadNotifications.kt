package dev.zipshare.upload

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ForegroundInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.zipshare.R
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UploadNotifications @Inject constructor(@ApplicationContext private val context: Context) {

    /**
     * Three channels rather than one, so the categories can also be silenced individually from
     * Android's own notification settings, not just from inside the app.
     */
    fun ensureChannels() {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        // Replaced by the three below; drop it so users are not left with a dead entry.
        runCatching { nm.deleteNotificationChannel("uploads") }

        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "Upload progress", NotificationManager.IMPORTANCE_LOW)
                .apply {
                    description = "Ongoing notification while a file is uploading."
                    setShowBadge(false)
                },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, "Upload completed", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "The link to a finished upload." },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_FAILED, "Upload failed", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "An upload could not be completed." },
        )
    }

    /**
     * Foreground work must carry a notification — Android gives no way to run without one — so
     * [detailed] = false does not remove it, it strips it back to a generic "Uploading" with no
     * file name, size or percentage.
     */
    fun foregroundInfo(id: Int, title: String, percent: Int, detailed: Boolean = true): ForegroundInfo {
        val builder = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (detailed) {
            builder.setContentTitle(title)
                .setContentText(if (percent >= 0) "$percent%" else "Uploading...")
                .setProgress(100, percent.coerceIn(0, 100), percent < 0)
        } else {
            builder.setContentTitle("Uploading").setProgress(0, 0, true)
        }

        val n = builder.build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, n)
        }
    }

    /**
     * Must NOT reuse the worker's foreground notification id: WorkManager cancels that id when the
     * worker finishes, which silently removed this notification a moment after it was posted.
     */
    fun success(name: String, url: String) {
        val id = nextResultId()
        val open = PendingIntent.getActivity(
            context,
            id,
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val share = PendingIntent.getActivity(
            context,
            id + SHARE_OFFSET,
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url),
                "Share link",
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val copy = PendingIntent.getBroadcast(
            context,
            id + COPY_OFFSET,
            Intent(context, ClipboardReceiver::class.java)
                .putExtra(ClipboardReceiver.EXTRA_URL, url)
                .putExtra(ClipboardReceiver.EXTRA_NOTIFICATION_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        notify(
            id,
            NotificationCompat.Builder(context, CHANNEL_DONE)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle(name)
                .setContentText(url)
                .setStyle(NotificationCompat.BigTextStyle().bigText(url))
                .setAutoCancel(true)
                .setContentIntent(open)
                .addAction(android.R.drawable.ic_menu_view, "Open", open)
                .addAction(android.R.drawable.ic_menu_save, "Copy", copy)
                .addAction(android.R.drawable.ic_menu_share, "Share", share)
                .build(),
        )
    }

    fun failure(name: String, message: String) {
        val id = nextResultId()
        notify(
            id,
            NotificationCompat.Builder(context, CHANNEL_FAILED)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("Upload failed - $name")
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setAutoCancel(true)
                .build(),
        )
    }

    /**
     * Every copied link is marked sensitive, not just password-protected ones: on a private
     * instance the URL *is* the secret, and this keeps it out of clipboard previews and
     * clipboard-history surfaces.
     */
    fun copyToClipboard(url: String) {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText("ZipShare", url)
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
        runCatching { cm.setPrimaryClip(clip) }
    }

    private fun notify(id: Int, notification: Notification) {
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    companion object {
        private val resultCounter = java.util.concurrent.atomic.AtomicInteger(0)
        private const val RESULT_ID_BASE = 900_000

        /** Result notifications live in their own id range: always > [RESULT_ID_BASE]. */
        internal fun nextResultId(): Int =
            RESULT_ID_BASE + 1 + (resultCounter.getAndIncrement() % 10_000)

        /**
         * Kept non-positive, so it can never collide with a result id. Reusing one would let
         * WorkManager's foreground teardown cancel the completion notification posted moments
         * earlier - which is exactly what happened before these two shared a formula.
         */
        internal fun foregroundId(workIdHash: Int): Int = -kotlin.math.abs(workIdHash)

        const val CHANNEL_PROGRESS = "uploads_progress"
        const val CHANNEL_DONE = "uploads_done"
        const val CHANNEL_FAILED = "uploads_failed"
        private const val SHARE_OFFSET = 100_000
        private const val COPY_OFFSET = 200_000
    }
}
