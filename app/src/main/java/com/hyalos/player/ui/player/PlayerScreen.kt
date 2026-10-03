package com.hyalos.player.ui.player

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.view.WindowManager
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import androidx.media3.ui.PlayerControlView
import androidx.media3.ui.PlayerView
// The controller's ids live in Media3's own R, not the app's: R classes are not
// transitive, so `R.id.exo_prev` does not exist on ours.
import androidx.media3.ui.R as Media3R
import com.hyalos.player.R
import com.hyalos.player.data.PlaybackOrientation
import com.hyalos.player.data.VideoScale
import com.hyalos.player.info.durationText
import com.hyalos.player.playback.PlaybackErrors
import com.hyalos.player.ui.common.InfoColors
import com.hyalos.player.ui.common.InfoSectionList
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

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
    var failed by remember { mutableStateOf(player.playerError != null) }

    // Held so the countdown below can reach into the view. Nothing else keeps a
    // handle on it: the `AndroidView` factory runs once and hands its result to
    // Compose, which passes it to `update` and nowhere else.
    var controller by remember { mutableStateOf<PlayerView?>(null) }

    /**
     * How far the controls are showing, 1 down to 0 — the title bar follows it.
     *
     * Read off the view rather than listened for, because Media3 hides its
     * controls by **sliding the bar off the bottom of the screen**: the hide
     * animator is `ObjectAnimator.ofTranslationY(…, bottomBar)`. Nothing about
     * the views' visibility changes, and nothing about their alpha, so there is
     * no event to subscribe to and no property to read — the offset is the
     * signal, and reading it is the only way to know.
     *
     * Both of the visibility listeners were tried first and neither is ever
     * called on an auto-hide; they do fire when the *user* hides the controls,
     * which is what made this look intermittent for a while.
     */
    var controlsShown by remember { mutableStateOf(true) }
    LaunchedEffect(controller) {
        while (true) {
            // The controller goes **GONE** when Media3 hides it. Measured on the
            // device rather than taken from the docs: a few seconds into
            // playback the view reports visibility 8 while the screen shows no
            // controls at all.
            //
            // Read rather than listened for, because the visibility listeners
            // `PlayerView` offers are never called for an auto-hide — both
            // overloads were wired up and both logged nothing — so a title bar
            // waiting on one of them never hides. Watching the property is the
            // only thing that works.
            //
            // Found by class, not by id: the axis of this is a `PlayerControlView`
            // among the `PlayerView`'s children, and its own id belongs to
            // Media3 rather than to us.
            val controlView = controller?.let { view ->
                (0 until view.childCount)
                    .map { view.getChildAt(it) }
                    .filterIsInstance<PlayerControlView>()
                    .firstOrNull()
            }
            controlsShown = controlView?.visibility == View.VISIBLE
            delay(CONTROLS_TICK_MS)
        }
    }

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
    // The mode the controller's button is on, and the name it announces. Read
    // from the settings, so the icon is a view of the same value the player is
    // acting on rather than a copy that could drift from it.
    val playbackMode by viewModel.playbackMode.collectAsState()
    val playbackModeName = stringResource(playbackMode.labelRes)

    val rotateTarget = if (portrait) PlaybackOrientation.LANDSCAPE else PlaybackOrientation.PORTRAIT
    val rotateLabel = stringResource(
        if (rotateTarget == PlaybackOrientation.LANDSCAPE) {
            R.string.player_rotate_to_landscape
        } else {
            R.string.player_rotate_to_portrait
        },
    )

    // The system's media volume is what the gesture moves, so the hardware keys
    // and the gesture agree about what the volume is.
    val context = LocalContext.current
    val audio = remember(context) { context.getSystemService(AudioManager::class.java) }

    Immersive()
    PlayerOrientation(orientationOverride ?: initialOrientation)
    ScreenBrightness(viewModel.screenLevels.brightness)

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

    Box(
    Modifier
        .fillMaxSize()
        .background(Color.Black)
        // The picture doubles as a control surface: up and down on the left half
        // is brightness, on the right half is volume.
        //
        // **On this box, which is an ancestor of the `AndroidView`** — not on an
        // overlay drawn above it. Compose hands an unconsumed event on to the
        // child, so a tap this ignores still reaches the `PlayerView` and still
        // brings the controls up. A sibling drawn on top would take every tap
        // itself and tapping the picture would never show the controls again.
        // The arithmetic is in `DragLevels`; this only wires it up.
        // Keyed on whether the info sheet is up: while it is, it owns the
        // screen — its own list scrolls vertically — and a drag on the panel or
        // the dim around it must not also be moving the brightness. Standing
        // down entirely is simpler to reason about than trying to tell the two
        // apart by where the finger is.
        .pointerInput(viewModel.info == null) {
            if (viewModel.info != null) return@pointerInput
            var target: DragTarget? = null
            var from = 0f
            var travelled = 0f

            // The stream has a handful of discrete steps and a drag is
            // continuous, so most frames land on the step already set. Writing
            // it anyway would be a binder call per frame for no change.
            var lastVolumeIndex = -1

            // Which way this gesture went, decided on its first movement and
            // then held. Null until there has been one.
            var axis: DragAxis? = null
            // Whether it may seek at all, asked once on the way down.
            var maySeek = false
            var seekFrom = 0L
            var seekTo = 0L
            var travelledX = 0f
            // Whether the finger is over a corner, kept as it goes past.
            // `onDragEnd` is handed no position, so the release has to be judged
            // by the last place the finger was *seen* — and keeping the answer
            // rather than recomputing it is what stops the readout, which is
            // told this every frame, from disagreeing with what the release does.
            var cornered = false

            // `detectDragGestures` rather than the vertical one, because this
            // gesture now has a direction to decide as well as a value. **Two
            // detectors cannot be stacked in one `pointerInput`** — each awaits
            // its own slop and the first to arrive blocks the other — so there is
            // one 2D detector and the arbitration is done here.
            detectDragGestures(
                onDragStart = { at ->
                    travelled = 0f
                    travelledX = 0f
                    lastVolumeIndex = -1
                    axis = null
                    cornered = false
                    // Both asked **once**, here, and held for the whole gesture:
                    // a finger that drifts out of the seek zone, or across the
                    // middle of the screen, must not hand the gesture to
                    // something else half way through.
                    //
                    // `at` is where the drag was recognised rather than where the
                    // finger landed — a touch slop's difference, and the closest
                    // thing to the press that any drag detector reports.
                    maySeek = seekZoneAt(at.x, size.width.toFloat())
                    val which = dragTargetAt(at.x, size.width.toFloat())
                    target = which
                    from = when (which) {
                        DragTarget.BRIGHTNESS -> currentBrightness(activity?.window, context)
                        DragTarget.VOLUME -> audio?.let {
                            levelForIndex(
                                it.getStreamVolume(AudioManager.STREAM_MUSIC),
                                it.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                            )
                        } ?: 0f
                    }
                    seekFrom = viewModel.positionMs
                    seekTo = seekFrom
                    // Nothing is shown yet: which readout it is depends on the
                    // direction, and that arrives with the first move — in the
                    // same frame, so nothing is lost by waiting for it.
                },
                onDrag = { change, amount ->
                    // Claimed, so the drag does not also reach the player behind
                    // and fire a tap once the finger lifts.
                    change.consume()
                    val way = axis ?: axisOf(amount.x, amount.y).also { axis = it }

                    if (way == DragAxis.HORIZONTAL) {
                        // The outer fifths, and a film of unknown length, simply
                        // do not seek. The gesture is still swallowed, so it
                        // cannot land as a tap on the controls behind.
                        if (!maySeek) return@detectDragGestures
                        val duration = viewModel.durationMs ?: return@detectDragGestures
                        travelledX += amount.x
                        seekTo = seekTargetAfter(
                            startMs = seekFrom,
                            dragPx = travelledX,
                            widthPx = size.width.toFloat(),
                            durationMs = duration,
                        )
                        // Where the finger is **now**, as against where it will
                        // be let go. The readout is the only thing that can warn
                        // someone that drifting into a corner is about to throw
                        // the seek away, and warning them at the end would be
                        // warning them too late to come back out.
                        cornered = inCorner(
                            x = change.position.x,
                            y = change.position.y,
                            width = size.width.toFloat(),
                            height = size.height.toFloat(),
                        )
                        viewModel.showSeek(SeekPreview(seekFrom, seekTo, cancelled = cornered))
                        return@detectDragGestures
                    }

                    val which = target ?: return@detectDragGestures
                    travelled += amount.y
                    when (which) {
                        DragTarget.BRIGHTNESS -> {
                            val level = brightnessAfter(from, travelled, size.height.toFloat())
                            // Plain var, not Compose state: writing it does not
                            // recompose, which is what keeps a sixty-times-a-
                            // second drag from redrawing the whole screen. The
                            // readout below has state of its own.
                            viewModel.screenLevels.brightness = level
                            activity?.window?.setBrightness(level)
                            viewModel.showLevel(which, level)
                        }

                        DragTarget.VOLUME -> audio?.let { manager ->
                            val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                            val index = volumeIndexFor(
                                levelAfter(from, travelled, size.height.toFloat()),
                                max,
                            )
                            if (index != lastVolumeIndex) {
                                // Flags 0 on purpose: the system's own volume
                                // panel would otherwise come up alongside this
                                // readout and say the same thing twice.
                                manager.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
                                lastVolumeIndex = index
                            }
                            viewModel.showLevel(which, levelForIndex(index, max))
                        }
                    }
                },
                onDragEnd = {
                    if (axis == DragAxis.HORIZONTAL) {
                        // **Where the finger stopped is what counts**, and that
                        // is [cornered] — the answer the last move already worked
                        // out, for the last place the finger was seen. Asking
                        // again here would be asking the same question twice, and
                        // leaving the readout free to disagree with the release.
                        //
                        // The corners are where the control bar's own buttons
                        // are, so a finger that ends there was more likely
                        // reaching for one of them than choosing a minute.
                        //
                        // A target equal to where the film already is is not
                        // sent: on a file over the network a seek re-opens the
                        // stream, and paying that for no movement is worse than
                        // the round trip it saves.
                        val kept = maySeek && !cornered && seekTo != seekFrom
                        if (kept) {
                            viewModel.seekTo(seekTo)
                            viewModel.releaseSeek()
                        } else {
                            viewModel.clearSeek()
                        }
                    } else {
                        viewModel.releaseLevel()
                    }
                },
                onDragCancel = {
                    // A cancelled gesture keeps nothing, seek included: whatever
                    // took the pointer away — the control bar's own progress bar
                    // claiming a move, a system edge swipe arriving, a second
                    // screen opening — did not mean to move the film. Unlike the
                    // readouts for brightness and volume, which *were* changing
                    // as the finger went, this one has nothing to linger over.
                    if (axis == DragAxis.HORIZONTAL) {
                        viewModel.clearSeek()
                    } else {
                        viewModel.releaseLevel()
                    }
                },
            )
        },
) {
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
                // they still overrun.
                //
                // So three of them sit this one out — and it is the **seek**
                // buttons that go, not the navigation ones. It used to be the
                // other way round, which left the row as play twice over and no
                // way to reach the next episode: on a phone held upright,
                // skipping ahead is what the row is for, and ±5s is a landscape
                // luxury. Nothing leaves the queue itself either way.
                val roomy = if (portrait) View.GONE else View.VISIBLE
                view.findViewById<View>(Media3R.id.exo_rew_with_amount)?.visibility = roomy
                view.findViewById<View>(Media3R.id.exo_ffwd_with_amount)?.visibility = roomy
                view.findViewById<View>(Media3R.id.exo_time)?.visibility = roomy
                // Upright these are the row, so they are asked for explicitly.
                // Media3 hides them by itself when there is nowhere to go.
                if (portrait) {
                    view.findViewById<View>(Media3R.id.exo_prev)?.visibility = View.VISIBLE
                    view.findViewById<View>(Media3R.id.exo_next)?.visibility = View.VISIBLE
                }
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
        if (controlsShown || failed) {
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
                // Three on the right, so a row rather than a single button — and
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
                    // Between the other two, and here rather than down in the
                    // control bar's row of transport buttons: those are about
                    // this film — play, seek, next — and this is about what
                    // happens after it. It sits with the other two "about the
                    // playback" buttons instead.
                    IconButton(
                        onClick = viewModel::cyclePlaybackMode,
                        colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White),
                    ) {
                        Icon(
                            painterResource(playbackMode.iconRes),
                            stringResource(R.string.player_playback_mode, playbackModeName),
                        )
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
        // After the bars, so the readout sits over them; before the info sheet,
        // which is a thing the user opened and should stay on top.
        PlayerLevelOverlay(viewModel)

        // The two readouts cannot both be up: the direction decides which one a
        // gesture gets, and it is decided once. They share the middle of the
        // screen for that reason.
        PlayerSeekOverlay(viewModel)

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
 *
 * The opt-in is for `AspectRatioFrameLayout` itself, whose constants these are —
 * it is one of the legacy views Media3 marks unstable and is steering callers
 * away from. It is needed here, and not only on `PlayerScreen`, because this is
 * a top-level function and inherits nothing from the composable that calls it.
 */
@OptIn(UnstableApi::class)
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
 * one. Three 48dp buttons plus the row's own padding, on the side that has
 * three: the padding is symmetric, so this is what keeps the title centred on
 * the screen rather than on the gap between unequal ends.
 */
private val TOP_BAR_BUTTON_ROOM = 160.dp

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
 * How often the control bar's slide is read.
 *
 * Faster than the countdown above, because this one is watched rather than read:
 * the slide takes about a fifth of a second, and at half a second the title
 * would sit there a beat after the bar under it had gone.
 */
private const val CONTROLS_TICK_MS = 100L

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

/**
 * Where a brightness drag starts from.
 *
 * The window's own value is `-1f` until the gesture has been used once, meaning
 * "follow the system" — and a drag that starts from there would jump the screen
 * to some arbitrary brightness on its first pixel. So when the window has no
 * opinion, the system's is read out and used as the starting point. **Reading it
 * needs no permission**; only writing does, which is why the gesture writes to
 * the window instead.
 */
private fun currentBrightness(window: Window?, context: Context): Float {
    val own = window?.attributes?.screenBrightness
        ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    if (own >= 0f) return own
    val system = Settings.System.getInt(
        context.contentResolver,
        Settings.System.SCREEN_BRIGHTNESS,
        SYSTEM_BRIGHTNESS_MIDPOINT,
    )
    return brightnessFromSetting(system)
}

private const val SYSTEM_BRIGHTNESS_MIDPOINT = 128

/**
 * Hold this window at [brightness], or at the system's when it is null.
 *
 * The value is the app-wide one rather than anything owned here, because it has
 * to survive going from one film to the next — see `ScreenLevels`. What is owned
 * here is only the *applying*: the window belongs to the activity, the player is
 * one screen inside it, and leaving must put the window back. Otherwise a film
 * watched in the dark would leave the file browser dimmed too.
 *
 * `onDispose` covers more than leaving the player. Covering it with the settings
 * page tears this composition down as well, so the window goes back to the
 * system value and is set again on the way in — which lands on the same answer
 * either way.
 */
@Composable
private fun ScreenBrightness(brightness: Float?) {
    val activity = LocalActivity.current
    DisposableEffect(activity, brightness) {
        val window = activity?.window
        window?.setBrightness(brightness)
        onDispose { window?.setBrightness(null) }
    }
}

/**
 * `-1f` is `BRIGHTNESS_OVERRIDE_NONE`: the window stops having an opinion and
 * the system's brightness shows through again.
 */
private fun Window.setBrightness(brightness: Float?) {
    attributes = attributes.apply {
        screenBrightness = brightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    }
}

/**
 * The brightness or volume readout, while a drag on the picture is adjusting it.
 *
 * A bar and a percentage rather than a slider: there is nothing to grab, the
 * finger is already doing the adjusting somewhere else on the screen, and what
 * is wanted from this is an answer to "how much", not another control. It sits
 * in the middle of the picture, where the eye already is.
 *
 * Drawn over everything, because it has to be readable against whatever frame
 * is playing.
 */
@Composable
private fun PlayerLevelOverlay(viewModel: PlayerViewModel) {
    // Read here rather than passed in, so that a drag recomposes this and not
    // the whole screen: the state is read inside the smallest composable that
    // needs it, which is the one that redraws sixty times a second.
    val feedback = viewModel.level ?: return
    val percent = (feedback.fraction * 100).roundToInt()
    val label = stringResource(
        when (feedback.target) {
            DragTarget.BRIGHTNESS -> R.string.player_brightness
            DragTarget.VOLUME -> R.string.player_volume
        },
    )

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(PLAYER_INFO_PANEL)
                .padding(horizontal = 24.dp, vertical = 16.dp)
                // One announcement for the whole panel. The icon, the bar and
                // the digits are the same fact three times over, and none of
                // the three says it in words.
                .semantics(mergeDescendants = true) {
                    contentDescription = "$label $percent%"
                },
        ) {
            Icon(
                painter = painterResource(
                    when (feedback.target) {
                        DragTarget.BRIGHTNESS -> R.drawable.ic_brightness
                        DragTarget.VOLUME -> R.drawable.ic_volume
                    },
                ),
                contentDescription = null,
                tint = Color.White,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "$percent%",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
            Spacer(Modifier.height(8.dp))
            // The same bar the control bar uses for position, so "how far along
            // this is" reads the same way in both places.
            LinearProgressIndicator(
                progress = { feedback.fraction },
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.3f),
                modifier = Modifier.width(PLAYER_LEVEL_BAR_WIDTH),
            )
        }
    }
}

/**
 * Where a sideways drag would land, while the finger is choosing it.
 *
 * Both ends of the jump, not just the target: a seek is a *move*, and the whole
 * of what is being chosen is how far. The bar underneath repeats it against the
 * film as a whole, so "that is most of the way in" is legible without reading
 * either number.
 *
 * The time is `durationText` — the same formatter the info dialog uses, and the
 * only one that gives an unsigned time. The control bar's own countdown carries
 * a minus sign and would read as going backwards.
 */
@Composable
private fun PlayerSeekOverlay(viewModel: PlayerViewModel) {
    // Read here rather than passed in, so that a drag recomposes this and not
    // the whole screen. See `PlayerLevelOverlay`.
    val preview = viewModel.seekPreview ?: return
    // Only ever null if the film stopped knowing its own length mid-gesture,
    // which cannot happen — but a fraction needs a denominator.
    val duration = viewModel.durationMs ?: return

    val forward = preview.toMs > preview.fromMs
    val label = stringResource(
        if (forward) R.string.player_seek_forward else R.string.player_seek_backward,
    )
    // The panel says one thing and it is whichever of the two is true at this
    // instant: the jump that letting go would make, or that letting go where the
    // finger is will make none. The arrow and the bar stay as they are either
    // way — they are the jump being offered, and being told it is about to be
    // thrown away is only useful beside what is being thrown away.
    val times = if (preview.cancelled) {
        stringResource(R.string.player_seek_cancelled)
    } else {
        stringResource(
            R.string.player_seek_times,
            durationText(preview.fromMs),
            durationText(preview.toMs),
        )
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(PLAYER_INFO_PANEL)
                .padding(horizontal = 24.dp, vertical = 16.dp)
                // One announcement for the whole panel: the arrow, the numbers
                // and the bar are the same fact three times over, and the arrow
                // on its own says nothing out loud. The cancelled line is
                // already a sentence, so it is not prefixed with the direction —
                // "快进 松手取消跳转" would announce a direction for a jump that
                // is not going to happen.
                .semantics(mergeDescendants = true) {
                    contentDescription =
                        if (preview.cancelled) times else "$label $times"
                },
        ) {
            Icon(
                painter = painterResource(
                    if (forward) R.drawable.ic_forward else R.drawable.ic_rewind,
                ),
                contentDescription = null,
                tint = Color.White,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = times,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
            Spacer(Modifier.height(8.dp))
            // The same bar, and the same width, as the brightness and volume
            // readout: this is the third thing a drag on the picture can do, and
            // it should not look like a different feature.
            LinearProgressIndicator(
                progress = { (preview.toMs.toFloat() / duration).coerceIn(0f, 1f) },
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.3f),
                modifier = Modifier.width(PLAYER_LEVEL_BAR_WIDTH),
            )
        }
    }
}

private val PLAYER_LEVEL_BAR_WIDTH = 140.dp

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
