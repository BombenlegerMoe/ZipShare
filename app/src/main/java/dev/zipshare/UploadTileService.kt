package dev.zipshare

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService

/** Quick Settings tile: one tap from the shade straight into the file picker. */
class UploadTileService : TileService() {

    override fun onClick() {
        if (isLocked) unlockAndRun(::launchPicker) else launchPicker()
    }

    private fun launchPicker() {
        val intent = Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_UPLOAD_FILE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}
