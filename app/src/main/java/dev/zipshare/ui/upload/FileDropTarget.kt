package dev.zipshare.ui.upload

import android.content.ClipDescription
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.zipshare.ui.findActivity

/**
 * Accepts files dragged in from another app - split screen, freeform windows, or a desktop-mode
 * display - and hands the uris to [onDropped].
 *
 * The drop only carries a *grant*, not the bytes, and that grant dies with this activity. Callers
 * must therefore consume the uris before then; [dev.zipshare.upload.UploadEnqueuer] does, because
 * a dragged uri cannot be persisted and so always takes the staging (copy) path.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.fileDropTarget(onDropped: (List<Uri>) -> Unit): Modifier {
    // Every remember below runs unconditionally. Returning early on a null activity would make the
    // number of slots this composable claims depend on a runtime value, which corrupts the slot
    // table if the same call site ever composes both ways.
    val activity by rememberUpdatedState(LocalContext.current.findActivity())
    val latest by rememberUpdatedState(onDropped)
    var hovered by remember { mutableStateOf(false) }

    val target = remember {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val host = activity ?: return false
                val dragEvent = event.toAndroidDragEvent()

                // Without this the uris are readable-looking but every open() throws: a cross-app
                // drag grants nothing until the receiving activity claims it.
                val granted = runCatching {
                    host.requestDragAndDropPermissions(dragEvent)
                }.getOrNull()

                val uris = dragEvent.clipData?.let { clip ->
                    (0 until clip.itemCount).mapNotNull { clip.getItemAt(it)?.uri }
                }.orEmpty()

                if (uris.isEmpty() || granted == null) {
                    // Hand the grant straight back rather than holding it until this activity dies:
                    // nothing here is going to read it.
                    granted?.release()
                    return false
                }

                latest(uris)
                return true
            }

            override fun onEntered(event: DragAndDropEvent) { hovered = true }

            override fun onExited(event: DragAndDropEvent) { hovered = false }

            override fun onEnded(event: DragAndDropEvent) { hovered = false }
        }
    }

    return this
        .dragAndDropTarget(shouldStartDragAndDrop = { it.carriesFiles() }, target = target)
        .then(
            // Border only, no clip: clipping here would round and crop the whole page for as long
            // as the drag hovers, which reads as a rendering glitch.
            if (hovered) {
                Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
            } else {
                Modifier
            },
        )
}

/**
 * Text drags (a selection from a browser, say) are refused: this target uploads files, and lighting
 * it up for something it would then reject is worse than staying dark.
 */
private fun DragAndDropEvent.carriesFiles(): Boolean {
    val description = toAndroidDragEvent().clipDescription ?: return false
    if (description.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST)) return true
    return (0 until description.mimeTypeCount).any { !description.getMimeType(it).startsWith("text/") }
}
