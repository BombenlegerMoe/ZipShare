package dev.zipshare.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Roughly a third of a phone screen, so the row lands with its heading visible above it. */
private const val BELOW_MARGIN_PX = 700f

/**
 * Marks a row as something search can jump to.
 *
 * When [focus] equals [id] the row scrolls itself into view and pulses twice, then goes quiet. A
 * search result that only opened the right *screen* still left you hunting for the row, which on
 * the settings page means scrolling past thirty controls.
 *
 * The pulse is deliberately short and repeats twice: one flash is easy to miss if the scroll
 * animation is still settling when it fires, and a highlight that stays put reads as a selection
 * the user has to dismiss.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FocusTarget(
    id: String,
    focus: String?,
    modifier: Modifier = Modifier,
    /**
     * Matches the spacing the wrapped rows had as direct children of the screen's own Column.
     * Sections here emit several siblings, so this has to be a Column - a Box stacked them on top
     * of each other and every label drew over the field below it.
     */
    spacing: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val requester = remember { BringIntoViewRequester() }
    val glow = remember { Animatable(0f) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(focus, id) {
        if (focus != id) return@LaunchedEffect
        // The row has to exist and be measured before it can be scrolled to; on a cold navigation
        // the first frame is still laying out.
        delay(180)
        runCatching {
            // Asking for the row plus a margin below it, rather than the row alone: bringIntoView
            // scrolls the minimum distance, which leaves the thing you searched for pinned to the
            // bottom edge of the screen with its section header off-screen above.
            requester.bringIntoView(
                Rect(0f, 0f, size.width.toFloat(), size.height + BELOW_MARGIN_PX),
            )
        }
        repeat(2) {
            glow.animateTo(1f, tween(200))
            glow.animateTo(0f, tween(400))
        }
    }

    Column(
        modifier
            .fillMaxWidth()
            .onGloballyPositioned { size = it.size }
            .bringIntoViewRequester(requester)
            .clip(RoundedCornerShape(8.dp))
            .background(
                MaterialTheme.colorScheme.primary.copy(alpha = 0.20f * glow.value),
            ),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        content()
    }
}
