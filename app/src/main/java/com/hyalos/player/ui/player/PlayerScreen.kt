package com.hyalos.player.ui.player

import android.content.pm.ActivityInfo
import androidx.activity.compose.LocalActivity
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.hyalos.player.R
import com.hyalos.player.data.VideoScale
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
fun PlayerScreen(
    viewModel: PlayerViewModel,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
) {
    val player = viewModel.player
    val videoScale by viewModel.videoScale.collectAsStateWithLifecycle()
    val title by viewModel.title.collectAsStateWithLifecycle()
    var controlsVisible by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(player.playerError != null) }

    Immersive()
    LandscapeForWideVideo(player)

    // No background playback yet, so leaving the app pauses.
    //
    // Except when the activity is being rebuilt for a configuration change. The
    // player lives in the ViewModel, which survives that rebuild, so pausing on
    // the way out would pause the *new* screen's player — and since this screen
    // rotates itself for widescreen video, that used to stop playback 24 ms
    // after it started. The manifest now claims orientation changes so this
    // cannot happen for rotation, but other changes (locale, split screen) can
    // still rebuild the activity and would fail the same way.
    val activity = LocalActivity.current
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (activity?.isChangingConfigurations != true) player.pause()
    }

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
            // Applied here rather than in the factory: the factory runs once, so
            // a later change to the setting would never reach the view.
            update = { view -> view.resizeMode = videoScale.toResizeMode() },
            // Detach so the view does not hold the player past this screen.
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize(),
        )

        // PlayerView's controller has no back button and no title; both go here,
        // and both come and go with the controls.
        if (controlsVisible || failed) {
            // A scrim first, so it sits under the row. White on a bright frame is
            // otherwise unreadable — true of an arrow, more so of a title.
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .height(TOP_SCRIM_HEIGHT)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.65f), Color.Transparent),
                        ),
                    ),
            )
            // Back, title and settings across one bar: the title centred on the
            // screen, the two controls at the ends. Centred rather than tucked
            // against the back button because a short name in the middle of a
            // wide black bar reads as a title, where one huddled at the left
            // reads as text that happens to be there.
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .safeDrawingPadding()
                    .height(TOP_BAR_HEIGHT),
            ) {
                IconButton(
                    onClick = onBack,
                    colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White),
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
                }
                IconButton(
                    onClick = onOpenSettings,
                    colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White),
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.player_settings))
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 1,
                    // Clip, not ellipsis: what an ellipsis would cut off is the
                    // part that says which episode this is — the end of the name,
                    // where the episode number usually sits.
                    overflow = TextOverflow.Clip,
                    modifier = Modifier
                        .align(Alignment.Center)
                        // Room for the two buttons, equal on both sides so the
                        // title is centred on the screen rather than in whatever
                        // space the buttons happen to leave.
                        .padding(horizontal = TOP_BAR_BUTTON_ROOM)
                        // Scrolls only when the name does not fit, so a short
                        // title is simply still.
                        .basicMarquee(),
                )
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
/**
 * The player's fitting mode for a chosen scale.
 *
 * `RESIZE_MODE_FILL` is the one that distorts, and `RESIZE_MODE_ZOOM` the one
 * that crops; both are deliberate choices in settings rather than something to
 * meet by accident.
 */
private fun VideoScale.toResizeMode(): Int = when (this) {
    VideoScale.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    VideoScale.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    VideoScale.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
}

/**
 * How far the scrim behind the back button and the title fades out.
 *
 * Fixed rather than measured from the row: the row sits below the display cutout
 * and the scrim does not, so a height that wraps the row would leave it starting
 * below the inset and the topmost strip of video unfaded. Generous enough to
 * cover the row on a device whose inset is large.
 */
private val TOP_SCRIM_HEIGHT = 120.dp

private val TOP_BAR_HEIGHT = 56.dp

/** Clearance for the back and settings buttons, so the title never sits under one. */
private val TOP_BAR_BUTTON_ROOM = 64.dp

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
