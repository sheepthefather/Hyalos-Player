package com.hyalos.player.ui.player

import android.content.pm.ActivityInfo
import androidx.activity.compose.LocalActivity
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.hyalos.player.R
import com.hyalos.player.playback.PlaybackErrors

/**
 * Full-screen playback through Media3's `PlayerView`.
 *
 * `PlayerView` rather than the Compose `Player` from media3-ui-compose-material3:
 * in Media3 1.11 that one is still experimental and lacks auto-hiding
 * controls, a buffering indicator and audio-track selection — the last of
 * which matters for films with more than one language.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(viewModel: PlayerViewModel, onBack: () -> Unit) {
    val player = viewModel.player
    var controlsVisible by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(player.playerError != null) }

    Immersive()
    LandscapeForWideVideo(player)
    // No background playback yet, so leaving the app pauses.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { player.pause() }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerErrorChanged(error: PlaybackException?) {
                failed = error != null
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { context ->
                PlayerView(context).apply {
                    this.player = player
                    keepScreenOn = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    setErrorMessageProvider(PlaybackErrors(context))
                    setControllerVisibilityListener(
                        PlayerView.ControllerVisibilityListener { visibility ->
                            controlsVisible = visibility == android.view.View.VISIBLE
                        },
                    )
                }
            },
            // Detach so the view does not hold the player past this screen.
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize(),
        )

        // PlayerView's controller has no back button; show one alongside it.
        if (controlsVisible || failed) {
            IconButton(
                onClick = onBack,
                colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White),
                modifier = Modifier.safeDrawingPadding().padding(4.dp),
            ) {
                Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
            }
        }

        if (failed) {
            Button(
                onClick = viewModel::retry,
                modifier = Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(32.dp),
            ) {
                Text(stringResource(R.string.retry))
            }
        }
    }
}

/** Hide the system bars while this screen is shown; a swipe brings them back briefly. */
@Composable
private fun Immersive() {
    val activity = LocalActivity.current ?: return
    DisposableEffect(activity) {
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

/**
 * Turn to landscape once the video turns out to be wider than tall, and give
 * the orientation back on leaving. Portrait video is left alone.
 */
@Composable
private fun LandscapeForWideVideo(player: Player) {
    val activity = LocalActivity.current ?: return
    DisposableEffect(activity, player) {
        fun apply(size: VideoSize) {
            if (size.width > 0 && size.width >= size.height) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        }
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) = apply(videoSize)
        }
        apply(player.videoSize)
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
}
