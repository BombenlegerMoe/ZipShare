package dev.zipshare.debug

import android.os.StrictMode

/**
 * Debug-only. Source set is `debug`, so this class does not exist in the release APK at all
 * and needs no ProGuard rule.
 *
 * detectLeakedSqlLiteObjects / detectLeakedClosableObjects are the two that catch a Room Cursor
 * or SQLiteConnection that was opened and never closed; penaltyLog puts the offending stack in
 * logcat under the "StrictMode" tag.
 */
object StrictModeSetup {

    fun install() {
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedSqlLiteObjects()
                .detectLeakedClosableObjects()
                .detectLeakedRegistrationObjects()
                .detectActivityLeaks()
                .detectFileUriExposure()
                .penaltyLog()
                .build(),
        )
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build(),
        )
    }
}
