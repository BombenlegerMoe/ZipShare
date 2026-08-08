package dev.zipshare.ui.viewer

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import kotlinx.coroutines.launch
import dev.zipshare.data.model.formatLink
import dev.zipshare.ui.shell.LocalLinkFormat
import androidx.core.net.toUri

/**
 * Full-screen viewer. Pinch to zoom, drag to pan once zoomed, double-tap to toggle 1x/2.5x,
 * single tap to hide the chrome.
 *
 * [previewUrl] is the authenticated raw URL - the Coil loader attaches the token header for the
 * active profile's host, so it works on private instances. [shareUrl] is the public link that gets
 * copied or shared; the two are deliberately different.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageViewerScreen(
    name: String,
    previewUrl: String,
    shareUrl: String,
    onBack: () -> Unit,
    /** Non-null for video/audio: the raw media URL, played instead of shown as a still. */
    playbackUrl: String? = null,
    vm: ViewerViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val playerClient by vm.client.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val linkFormat = LocalLinkFormat.current

    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var chromeVisible by remember { mutableStateOf(true) }

    fun reset() {
        scale = 1f
        offsetX = 0f
        offsetY = 0f
    }

    Scaffold(
        containerColor = Color.Black,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            AnimatedVisibility(visible = chromeVisible) {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Black.copy(alpha = 0.65f),
                        titleContentColor = Color.White,
                        navigationIconContentColor = Color.White,
                        actionIconContentColor = Color.White,
                    ),
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    },
                    title = {
                        Text(
                            name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                clipboard.setText(
                                    androidx.compose.ui.text.AnnotatedString(
                                        formatLink(name, shareUrl, linkFormat),
                                    ),
                                )
                                scope.launch { snackbar.showSnackbar("Link copied") }
                            },
                            colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White),
                        ) { Icon(Icons.Filled.ContentCopy, "Copy link") }

                        IconButton(
                            onClick = {
                                context.startActivity(
                                    Intent.createChooser(
                                        Intent(Intent.ACTION_SEND)
                                            .setType("text/plain")
                                            .putExtra(Intent.EXTRA_TEXT, shareUrl),
                                        "Share link",
                                    ),
                                )
                            },
                            colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White),
                        ) { Icon(Icons.Filled.Share, "Share") }

                        IconButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, shareUrl.toUri()),
                                    )
                                }
                            },
                            colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White),
                        ) { Icon(Icons.Filled.OpenInBrowser, "Open in browser") }
                    },
                )
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 6f)
                        if (scale > 1f) {
                            offsetX += pan.x
                            offsetY += pan.y
                        } else {
                            offsetX = 0f
                            offsetY = 0f
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { chromeVisible = !chromeVisible },
                        onDoubleTap = { if (scale > 1f) reset() else scale = 2.5f },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            if (playbackUrl != null) {
                // Zoom/pan gestures deliberately do not apply here: the player has its own
                // controls, and a transformed SurfaceView fights them.
                val client = playerClient
                if (client == null) {
                    CircularProgressIndicator(Modifier.size(48.dp), color = Color.White)
                } else {
                    VideoPlayer(
                        url = playbackUrl,
                        client = client,
                        modifier = Modifier.fillMaxSize().padding(padding),
                    )
                }
                return@Box
            }

            SubcomposeAsyncImage(
                model = previewUrl,
                contentDescription = name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(if (chromeVisible) padding else androidx.compose.foundation.layout.PaddingValues(0.dp))
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offsetX
                        translationY = offsetY
                    },
            ) {
                when (painter.state) {
                    is AsyncImagePainter.State.Loading ->
                        CircularProgressIndicator(
                            Modifier.size(48.dp).align(Alignment.Center),
                            color = Color.White,
                        )

                    is AsyncImagePainter.State.Error ->
                        Text(
                            "Could not load this image.",
                            color = Color.White,
                            modifier = Modifier.align(Alignment.Center),
                        )

                    else -> SubcomposeAsyncImageContent()
                }
            }
        }
    }
}
