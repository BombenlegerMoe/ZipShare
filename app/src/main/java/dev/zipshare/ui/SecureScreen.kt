package dev.zipshare.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * Counts how many screens currently need `FLAG_SECURE`.
 *
 * The flag is one window-wide toggle, but several screens can hold it: the lock screen composes
 * over whatever was open, and that may itself be the server editor or the two-factor QR. Setting
 * and clearing it directly makes the *first* screen to leave clear it for everyone still showing a
 * credential.
 *
 * Measured on device, today's navigation happens to avoid that: unlocking returns to Home, so the
 * screen underneath is gone by the time the lock screen disposes. That is a property of where
 * navigation currently lands, not of this code - counting makes the flag correct without depending
 * on it, so a later change to restore the previous screen on unlock cannot silently expose a token.
 *
 * Composition is single-threaded on the main thread, so a plain Int needs no synchronisation.
 */
internal object SecureWindow {

    private var active = 0

    fun acquire() {
        active++
    }

    /** True when that was the last holder and the flag should be cleared. */
    fun release(): Boolean {
        // Never below zero: a stray release must not make the next acquire look like the first.
        active = (active - 1).coerceAtLeast(0)
        return active == 0
    }

    internal fun activeCount(): Int = active

    internal fun reset() {
        active = 0
    }
}

/** FLAG_SECURE while this composable is on screen: no screenshots, no recents thumbnail. */
@Composable
fun SecureScreen() {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        SecureWindow.acquire()
        // Set unconditionally rather than only for the first holder: it is idempotent, and if the
        // activity was recreated the new window needs the flag even though the count is already up.
        window?.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        onDispose {
            if (SecureWindow.release()) {
                window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
