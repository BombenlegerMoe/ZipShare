package dev.zipshare.ui.viewer

import android.app.Activity
import android.app.PictureInPictureParams
import android.os.Build
import android.util.Rational
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import dev.zipshare.ui.findActivity
import okhttp3.OkHttpClient


/**
 * Plays a file straight off the server.
 *
 * The player is given the profile's own OkHttp client, so the `authorization` header, the TLS
 * policy and any certificate pin all apply to the media stream exactly as they do to the API.
 * Without this the token would have to ride in the URL, which is precisely what this app avoids.
 *
 * Playback survives leaving the app, via picture-in-picture - see [enterPip].
 */
// media3 marks its unstable API with androidx's RequiresOptIn, not Kotlin's, so kotlin.OptIn
// silences the compiler but leaves lint reporting every call site as an error.
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
fun VideoPlayer(url: String, client: OkHttpClient, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val inPip = rememberIsInPip()

    val player = remember(url, client) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(OkHttpDataSource.Factory(client)),
            )
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(url))
                playWhenReady = true
                prepare()
            }
    }

    DisposableEffect(player) { onDispose { player.release() } }

    // Where the video sits on screen, so the system can animate the shrink from the real frame
    // instead of the whole window. Kept as state because it is only known after layout.
    var videoBounds by remember { mutableStateOf<Rect?>(null) }

    // Registering the params up front is what lets Android dock automatically when the user leaves
    // during playback (API 31+); without it PiP only ever happens on the explicit button.
    LaunchedEffect(activity, videoBounds, player.videoSize) {
        activity?.updatePipParams(player.videoSize.width, player.videoSize.height, videoBounds)
    }

    Box(modifier) {
        AndroidView(
            modifier = Modifier
                .matchParentSize()
                .onGloballyPositioned { videoBounds = it.boundsInWindow() },
            factory = { ctx -> PlayerView(ctx).apply { this.player = player } },
            update = { view ->
                view.player = player
                // The transport controls are larger than the PiP window itself, and the system
                // already puts a play/pause action on it.
                view.useController = !inPip
                if (inPip) view.hideController()
            },
        )

        if (activity != null && !inPip) {
            IconButton(
                onClick = { activity.enterPip(player.videoSize.width, player.videoSize.height, videoBounds) },
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            ) {
                Icon(
                    Icons.Filled.PictureInPictureAlt,
                    contentDescription = "Play in a floating window",
                    tint = Color.White,
                )
            }
        }
    }
}

/**
 * Tracks picture-in-picture state so the player can drop its chrome while docked.
 *
 * Docking is a configuration change (the window resizes), so reading [LocalConfiguration] is enough
 * to recompose on entry and exit - no listener to register or unregister. The activity itself is
 * not recreated, because the manifest declares those configChanges; otherwise playback would
 * restart from zero every time the window docked.
 */
@Composable
private fun rememberIsInPip(): Boolean {
    val activity = LocalContext.current.findActivity()
    val configuration = LocalConfiguration.current
    return remember(configuration, activity) { activity?.isInPictureInPictureMode == true }
}

/**
 * Docks the activity, sized to the video.
 *
 * Android rejects an aspect ratio outside roughly 1:2.39 .. 2.39:1 with an
 * [IllegalArgumentException], so an unusually wide or tall video is clamped rather than left to
 * crash the viewer. A video whose size is not known yet falls back to 16:9.
 */
private fun Activity.enterPip(width: Int, height: Int, source: Rect?) {
    // Docking can still be refused - the device may have PiP disabled in settings, or the activity
    // may not be in the foreground by the time this lands.
    runCatching { enterPictureInPictureMode(pipParams(width, height, source)) }
}

/**
 * Publishes the params without docking, which is what auto-enter needs.
 *
 * From Android 12 the system docks on its own when the user leaves during playback, but only if
 * the activity has already declared its params - so this runs as soon as the video's size and
 * on-screen position are known, not at the moment the button is pressed.
 */
private fun Activity.updatePipParams(width: Int, height: Int, source: Rect?) {
    runCatching { setPictureInPictureParams(pipParams(width, height, source)) }
}

private fun Activity.pipParams(width: Int, height: Int, source: Rect?): PictureInPictureParams {
    val (numerator, denominator) = pipAspect(width, height)
    return PictureInPictureParams.Builder()
        .setAspectRatio(Rational(numerator, denominator))
        .apply {
            // The rect the window shrinks *from*. Without it the animation flies out of the whole
            // screen rather than the video frame, which is the poor transition lint warns about.
            source?.let { setSourceRectHint(android.graphics.Rect(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt())) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setAutoEnterEnabled(true)
        }
        .build()
}

/**
 * The aspect ratio to dock at, as numerator to denominator, clamped to what PiP will accept.
 *
 * Separate from [enterPip] and free of [Rational] so the clamping can be tested on the jvm: it is
 * the one part that throws rather than degrades if it is wrong.
 */
internal fun pipAspect(width: Int, height: Int): Pair<Int, Int> = when {
    width <= 0 || height <= 0 -> 16 to 9
    width.toFloat() / height > MAX_PIP_RATIO -> 239 to 100
    height.toFloat() / width > MAX_PIP_RATIO -> 100 to 239
    else -> width to height
}

/** The widest ratio Android's PiP accepts, in either orientation. */
private const val MAX_PIP_RATIO = 2.39f
