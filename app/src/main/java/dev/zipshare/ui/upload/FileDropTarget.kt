package dev.zipshare.ui.upload

import android.content.ClipDescription
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.zipshare.ui.findActivity
import androidx.compose.foundation.shape.RoundedCornerShape

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
    val activity = LocalContext.current.findActivity() ?: return this
    val latest by rememberUpdatedState(onDropped)
    var hovered by remember { mutableStateOf(false) }

    val target = remember {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val android = event.toAndroidDragEvent()

                // Without this the uris are readable-looking but every open() throws: a cross-app
                // drag grants nothing until the receiving activity claims it.
                val granted = runCatching {
                    activity.requestDragAndDropPermissions(android)
                }.getOrNull()

                val clip = android.clipData ?: return false
                val uris = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it)?.uri }
                if (uris.isEmpty() || granted == null) return false

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
            if (hovered) {
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
            } else {
                Modifier
            },
        )
}

/**
 * Text drags (a selection from a browser, say) are refused: this target uploads files, and lighting
 * it up for something it would then reject is worse than staying dark.
 */
private fun DragAndDropEvent.carriesFiles(): Boolean =
    toAndroidDragEvent().clipDescription?.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST) == true ||
        toAndroidDragEvent().clipDescription?.let { d ->
            (0 until d.mimeTypeCount).any { !d.getMimeType(it).startsWith("text/") }
        } == true
