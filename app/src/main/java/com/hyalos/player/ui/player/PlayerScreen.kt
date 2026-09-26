package com.hyalos.player.ui.player

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
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
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
// The controller's ids live in Media3's own R, not the app's: R classes are not
// transitive, so `R.id.exo_prev` does not exist on ours.
import androidx.media3.ui.R as Media3R
import com.hyalos.player.R
import com.hyalos.player.data.PlaybackOrientation
import com.hyalos.player.data.VideoScale
import com.hyalos.player.playback.PlaybackErrors
import com.hyalos.player.ui.common.InfoColors
import com.hyalos.player.ui.common.InfoSectionList
import kotlinx.coroutines.delay

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
    val initialOrientation by viewModel.initialOrientation.collectAsStateWithLifecycle()
    val orientationOverride by viewModel.orientationOverride.collectAsStateWithLifecycle()
    var controlsVisible by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(player.playerError != null) }

    // Held so the countdown below can reach into the view. Nothing else keeps a
    // handle on it: the `AndroidView` factory runs once and hands its result to
    // Compose, which passes it to `update` and nowhere else.
    var controller by remember { mutableStateOf<PlayerView?>(null) }

    // The remaining-time readout is ours, so no one else refreshes it: Media3
    // binds the time views it knows by id and has never heard of this one. Ticks
    // only while this screen is composed, which is the only time it is visible.
    LaunchedEffect(controller) {
        val label = controller?.findViewById<TextView>(R.id.player_remaining) ?: return@LaunchedEffect
        while (true) {
            label.text = remainingText(player.duration, player.currentPosition).orEmpty()
            delay(REMAINING_TICK_MS)
        }
    }

    // Where the rotate button would take the picture, which is also what its
    // icon and its description say. Read from the configuration rather than from
    // the two values above: the screen can also have been turned by the system,
    // and what the button means is "the other way from what is on screen now".
    val portrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
    val rotateTarget = if (portrait) PlaybackOrientation.LANDSCAPE else PlaybackOrientation.PORTRAIT
    val rotateLabel = stringResource(
        if (rotateTarget == PlaybackOrientation.LANDSCAPE) {
            R.string.player_rotate_to_landscape
        } else {
            R.string.player_rotate_to_portrait
        },
    )

    Immersive()
    PlayerOrientation(orientationOverride ?: initialOrientation)

    // No background playback yet, so leaving the app pauses.
    //
    // This fires for more than leaving the app: covering the player with another
    // screen stops this entry's lifecycle too, so tapping the settings gear
    // pauses the film. Measured, not assumed — the log shows
    // `playWhenReady=false reason=1` at the moment the settings page opens.
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

    // This entry stopping is also this entry being covered and rebuilt — the
    // same round trip that leaves a finished film with a new surface and nothing
    // to put on it. See `redrawIfFinished`.
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.redrawIfFinished() }

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
                // Inflated rather than built in code, because the controller
                // layout is named by a styleable and Media3 exposes no setter for
                // it. See `player_controller.xml`.
                (LayoutInflater.from(context).inflate(R.layout.player_view, null) as PlayerView).apply {
                    controller = this
                    this.player = player
                    keepScreenOn = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    setErrorMessageProvider(PlaybackErrors(context))
                    setControllerVisibilityListener(
                        PlayerView.ControllerVisibilityListener { visibility ->
                            controlsVisible = visibility == android.view.View.VISIBLE
                        },
                    )
                    // Our own button in the controller layout. The direction is
                    // read from the context at tap time rather than captured:
                    // this factory runs once, so a captured direction would be
                    // whichever way the screen pointed when the film opened.
                    findViewById<ImageButton>(R.id.player_orientation)?.setOnClickListener {
                        viewModel.toggleOrientation(
                            context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
                        )
                    }
                }
            },
            // Applied here rather than in the factory: the factory runs once, so
            // a later change to the setting would never reach the view.
            update = { view ->
                view.resizeMode = videoScale.toResizeMode()
                view.findViewById<ImageButton>(R.id.player_orientation)?.let { button ->
                    button.setImageResource(
                        if (rotateTarget == PlaybackOrientation.LANDSCAPE) {
                            R.drawable.ic_orientation_landscape
                        } else {
                            R.drawable.ic_orientation_portrait
                        },
                    )
                    button.contentDescription = rotateLabel
                }
                // Upright there is no room for the whole row. Measured: five
                // 52dp buttons, the time and the two icons on the right come to
                // 1530px against a 1080px screen, and even with the time gone
                // they still overrun. So the two navigation buttons and the time
                // sit this one out. Nothing leaves the queue itself — the film
                // still advances, and the buttons return with the landscape.
                val crowd = if (portrait) View.GONE else View.VISIBLE
                view.findViewById<View>(Media3R.id.exo_prev)?.visibility = crowd
                view.findViewById<View>(Media3R.id.exo_next)?.visibility = crowd
                view.findViewById<View>(Media3R.id.exo_time)?.visibility = crowd
                // What the time it hides is replaced by, upright.
                view.findViewById<View>(R.id.player_remaining)?.visibility =
                    if (portrait) View.VISIBLE else View.GONE
            },
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
                // Two on the right, so a row rather than a single button — and
                // the title's clearance is measured from the wider side, or it
                // would sit under one of them.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
                ) {
                    IconButton(
                        onClick = viewModel::showInfo,
                        colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White),
                    ) {
                        Icon(painterResource(R.drawable.ic_info), stringResource(R.string.player_info))
                    }
                    IconButton(
                        onClick = onOpenSettings,
                        colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White),
                    ) {
                        Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.player_settings))
                    }
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
                        // Room for the buttons, equal on both sides so the title
                        // is centred on the screen rather than in whatever space
                        // the buttons happen to leave. Sized for the right, which
                        // has two of them.
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

        // Last in the box, so it is over everything: the picture, the control
        // bar and the title bar. Drawn before the `AndroidView` it would be
        // behind the surface and never seen at all.
        PlayerInfoOverlay(viewModel)
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

/**
 * Clearance for the buttons either side of the title, so it never sits under
 * one. Two 48dp buttons plus the row's own padding, on the side that has two:
 * the padding is symmetric, so this is what keeps the title centred on the
 * screen rather than on the gap between unequal ends.
 */
private val TOP_BAR_BUTTON_ROOM = 112.dp

/**
 * How often the remaining-time readout is rewritten.
 *
 * Twice a second, against the once Media3 uses for its own time views: those sit
 * beside a progress bar that is already moving, while this one is the only
 * thing on a portrait screen that says the film is running, and at once a second
 * a pause can look like it did not take.
 */
private const val REMAINING_TICK_MS = 500L

/**
 * What the file is, and what the player is doing with it.
 *
 * On a scrim rather than in a themed dialog: a light Material surface in the
 * middle of a film is a different app for a moment, and this is the player's own
 * furniture — white on black, like the control bar underneath it.
 *
 * Dismissed by a tap anywhere, or by back. No button and no hint: tapping the
 * picture to make what is over it go away is the one gesture every player
 * already has, and back is the second.
 *
 * The rows were laid out once, when the sheet was opened — and the film was
 * paused then, deliberately. Nothing here is live, so nothing re-reads it.
 */
@Composable
private fun PlayerInfoOverlay(viewModel: PlayerViewModel) {
    val sections = viewModel.info ?: return

    BackHandler { viewModel.dismissInfo() }
    // The whole screen is a target, but not a sheet: a press on the picture
    // closes this, and the panel below is what is seen. Nothing is dimmed, so
    // the film stays the film.
    Box(
        Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                // No ripple: it would read as the picture itself being pressed.
                indication = null,
                onClick = viewModel::dismissInfo,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxWidth(PLAYER_INFO_WIDTH_FRACTION)
                .fillMaxHeight(PLAYER_INFO_HEIGHT_FRACTION)
                .clip(RoundedCornerShape(16.dp))
                .background(PLAYER_INFO_PANEL)
                // Swallows taps so a press inside the panel — on a label, on the
                // padding — does not close it. Only the picture around it does.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
        ) {
            // The scroll takes the drags; a tap is left for the panel's own
            // no-op, and one outside it for the screen behind.
            InfoSectionList(
                sections = sections,
                colors = PLAYER_INFO_COLORS,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * A panel, not a sheet: the picture stays visible on all four sides, which is
 * what says the film is still there and this is over it. Wide enough for a path
 * on one line in landscape, tall enough to show most of the rows before they
 * scroll.
 */
private const val PLAYER_INFO_WIDTH_FRACTION = 0.6f
private const val PLAYER_INFO_HEIGHT_FRACTION = 0.64f

/** Dark enough that white text reads over any frame, transparent enough to see it. */
private val PLAYER_INFO_PANEL = Color.Black.copy(alpha = 0.8f)

/** The player's own palette: no theme, because there is no surface under it. */
private val PLAYER_INFO_COLORS = InfoColors(
    section = Color.White,
    label = Color.White.copy(alpha = 0.7f),
    value = Color.White,
)

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
 * Hold the screen the way the setting asks, or the way the rotate button last
 * asked — that is already decided by the caller, which passes one or the other.
 *
 * No longer looks at the video's shape. It used to turn landscape whenever the
 * frame was wider than tall, which made the setting either redundant (for films)
 * or contradictory (for anything shot upright); now the answer comes from the
 * setting, and the button on the controller is how the other answer is reached.
 *
 * The sensors, not the fixed constants: `SENSOR_LANDSCAPE` allows either
 * landscape, so turning the phone over does not leave the picture upside down.
 */
@Composable
private fun PlayerOrientation(wanted: PlaybackOrientation) {
    val activity = LocalActivity.current ?: return
    DisposableEffect(activity, wanted) {
        activity.requestedOrientation = when (wanted) {
            PlaybackOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            PlaybackOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
        // Empty on purpose: the undo lives in the effect below, keyed on the
        // activity alone. Undoing here would run on every change of direction,
        // flashing the system's choice before the new one landed.
        onDispose { }
    }
    DisposableEffect(activity) {
        onDispose { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
}
