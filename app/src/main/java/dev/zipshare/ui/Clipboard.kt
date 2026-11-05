package dev.zipshare.ui

import android.content.ClipData
import android.content.ClipDescription
import android.os.PersistableBundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import kotlinx.coroutines.launch

/**
 * A plain-text copy for click handlers. [LocalClipboard] is suspend-only, so this launches on the
 * composition's scope; the clip is the same "plain text" one the old ClipboardManager.setText made.
 */
@Composable
fun rememberCopyText(
    /** Keeps the text out of the clipboard preview and keyboard history - for secrets. */
    sensitive: Boolean = false,
): (String) -> Unit {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    return remember(clipboard, scope, sensitive) {
        { text ->
            val clip = ClipData.newPlainText("plain text", text)
            if (sensitive) {
                clip.description.extras = PersistableBundle().apply {
                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
            }
            scope.launch { clipboard.setClipEntry(ClipEntry(clip)) }
        }
    }
}
