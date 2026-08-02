package dev.zipshare.ui.viewer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import okhttp3.OkHttpClient

/**
 * Plays a file straight off the server.
 *
 * The player is given the profile's own OkHttp client, so the `authorization` header, the TLS
 * policy and any certificate pin all apply to the media stream exactly as they do to the API.
 * Without this the token would have to ride in the URL, which is precisely what this app avoids.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(url: String, client: OkHttpClient, modifier: Modifier = Modifier) {
    val context = LocalContext.current

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

    AndroidView(
        modifier = modifier,
        factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = true } },
        update = { it.player = player },
    )
}
