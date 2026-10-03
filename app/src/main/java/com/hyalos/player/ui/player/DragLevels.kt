package com.hyalos.player.ui.player

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What a drag on the picture means, as arithmetic — brightness, volume and the
 * seek.
 *
 * The whole of the gesture is a handful of questions — which way it went, which
 * part of the screen the finger went down on, how far it moved, where it let go,
 * and what that makes the value — and none of them need a `Composable` to
 * answer. They live here for the same reason `ScrollBarGeometry` does: the
 * wiring is dull and the arithmetic is where the mistakes are, so the arithmetic
 * is the part that can be tested without a screen.
 *
 * **Direction and position answer different questions.** The direction decides
 * *what* happens — sideways seeks, up and down adjusts — while the position the
 * finger went down on decides *which* value, and whether a seek is allowed at
 * all. The two are independent, which is why they are separate functions here
 * rather than one "what does this drag mean".
 */

/** Which of the two things a drag on the picture adjusts. */
internal enum class DragTarget { BRIGHTNESS, VOLUME }

/**
 * Which way a drag went, decided once and then held.
 *
 * **Decided on the first movement and never revisited.** A drag that is read
 * afresh every frame switches the moment it wanders across the diagonal, so a
 * slightly curved swipe would seek for a while, then start changing the volume,
 * then seek again — three things from one gesture, none of them what was meant.
 */
internal enum class DragAxis { HORIZONTAL, VERTICAL }

/**
 * The dominant direction of a movement.
 *
 * A tie counts as vertical, which keeps a perfectly diagonal drag doing what
 * this screen did before there was anything else it could do.
 */
internal fun axisOf(dx: Float, dy: Float): DragAxis =
    if (abs(dx) > abs(dy)) DragAxis.HORIZONTAL else DragAxis.VERTICAL

/**
 * Whether a drag starting at [x] may seek.
 *
 * The middle three fifths. Seeking is the only gesture here that can be
 * triggered from the edges, and the edges are where the system keeps its own
 * back gesture — a swipe inwards from the left is not a request to rewind.
 *
 * Asked once, when the finger goes down, for the same reason [dragTargetAt] is:
 * drifting out of the zone mid-drag must not turn the gesture into something
 * else.
 */
internal fun seekZoneAt(x: Float, width: Float): Boolean =
    width > 0f && x >= width / 5f && x <= width * 4f / 5f

/**
 * How much of each side counts as a corner, for [inCorner].
 *
 * A fifth of the width and a fifth of the height. Big enough that a release
 * meant for one of the control bar's buttons lands in it, small enough that the
 * middle of the picture — where a seek is actually aimed — is nowhere near it.
 */
internal const val CORNER_FRACTION = 0.2f

/**
 * Whether a gesture released at ([x], [y]) ended in one of the four corners.
 *
 * **Only asked at the end, about where the finger stopped** — not about where it
 * has been. A drag that passes through a corner on its way somewhere else is a
 * drag; one that stops there is more likely to have been a tap that slipped, or
 * a reach for a button at the top of the screen.
 *
 * Only the seek consults this. A brightness or a volume drag that ends oddly is
 * undone by dragging back; a seek is a jump of minutes, and the two are not
 * worth the same care.
 */
internal fun inCorner(x: Float, y: Float, width: Float, height: Float): Boolean {
    if (width <= 0f || height <= 0f) return false
    val edgeX = width * CORNER_FRACTION
    val edgeY = height * CORNER_FRACTION
    return (x <= edgeX || x >= width - edgeX) && (y <= edgeY || y >= height - edgeY)
}

/**
 * What the seek readout shows: where it was, and where letting go would land —
 * or, when [cancelled], that letting go here will not move the film at all.
 */
internal data class SeekPreview(
    val fromMs: Long,
    val toMs: Long,
    /**
     * Whether the finger is in one of the four corners **at this moment**.
     *
     * Live, not decided at the end. Where the finger is now is the whole of what
     * decides it, and the readout is the only thing that can say so *while there
     * is still time to do something about it* — coming back out of the corner
     * puts the seek back. Said at the end it would be said too late to act on.
     */
    val cancelled: Boolean = false,
)

/**
 * Where a horizontal drag of [dragPx] from [startMs] would land.
 *
 * **A tenth of the film per screen width.** Short and long films then feel the
 * same: a full swipe is three minutes into a half-hour episode and twelve into a
 * two-hour film. A fixed number of seconds would be either too coarse for the
 * one or too little for the other, and the control bar's scrubber is still there
 * for crossing a film in one go.
 *
 * Clamped to the film at both ends. Running off the start or the end should stop
 * at the start or the end, not wrap.
 *
 * A film whose length is not known yet does not move at all: without a duration
 * there is no "tenth of it" to take, and seeking somewhere arbitrary on the
 * strength of a swipe is worse than not seeking.
 */
internal fun seekTargetAfter(
    startMs: Long,
    dragPx: Float,
    widthPx: Float,
    durationMs: Long,
): Long {
    if (durationMs <= 0L || widthPx <= 0f) return startMs
    val span = durationMs.toFloat() / SEEK_SCREENS
    return (startMs + (dragPx / widthPx) * span).toLong().coerceIn(0L, durationMs)
}

/** How many screen widths make one whole film. See [seekTargetAfter]. */
internal const val SEEK_SCREENS = 10f

/** What the on-screen readout is showing while a drag is happening. */
internal data class LevelFeedback(val target: DragTarget, val fraction: Float)

/**
 * How long the readout stays after the finger lifts.
 *
 * Not zero: the number has just been set and there has to be time to read it.
 * Long enough to read, short enough not to sit over the picture.
 */
internal const val LEVEL_LINGER_MS = 1_500L

/**
 * Which half [x] is in, on a screen [width] wide.
 *
 * **Asked once, when the finger goes down**, and the answer holds for the whole
 * gesture. Asking again as the finger moves would hand a drag that crossed the
 * middle over to the other value half way through — the brightness would stop
 * moving and the volume would start, from a level the user never chose.
 *
 * The middle itself counts as volume, which is arbitrary but has to be
 * something, and being definite beats being clever.
 */
internal fun dragTargetAt(x: Float, width: Float): DragTarget =
    if (x < width / 2f) DragTarget.BRIGHTNESS else DragTarget.VOLUME

/**
 * The value after a vertical drag of [dragPx] from [start], on a screen
 * [heightPx] tall.
 *
 * A full screen height is a full range, and dragging **up** raises the value —
 * `dragPx` is positive downwards, as it is on the screen, so it is subtracted.
 *
 * Clamped at both ends rather than wrapping: a drag that runs off the screen
 * should sit at the end it ran off, and letting go there should leave it there.
 */
internal fun levelAfter(start: Float, dragPx: Float, heightPx: Float): Float {
    if (heightPx <= 0f) return start.coerceIn(0f, 1f)
    return (start - dragPx / heightPx).coerceIn(0f, 1f)
}

/**
 * The lowest the brightness may be dragged to.
 *
 * Not zero. At zero the picture is black and so is everything that would let
 * someone drag it back up — they would have to leave the player to recover, and
 * nothing on screen would explain why. Just above nothing still reads as "as
 * dark as this goes".
 */
internal const val MIN_BRIGHTNESS = 0.02f

/** [levelAfter], floored so the screen can always be got back. */
internal fun brightnessAfter(start: Float, dragPx: Float, heightPx: Float): Float =
    levelAfter(start, dragPx, heightPx).coerceAtLeast(MIN_BRIGHTNESS)

/** The system brightness scale runs 0..255 and is not a fraction. */
internal const val SYSTEM_BRIGHTNESS_RANGE = 255f

/**
 * The system's brightness setting as a `0..1` level.
 *
 * Only ever used as the starting point of the **first** drag. Until then the
 * window has no brightness of its own — it says `-1f`, "follow the system" —
 * and a drag that started from that would jump the picture to some arbitrary
 * value on its first pixel. Afterwards the level is remembered and this is not
 * consulted again.
 *
 * Two things it is not: it does not account for adaptive brightness, which can
 * leave the screen at a different brightness than this says; and 255 is not
 * guaranteed to be every device's maximum. Both are tolerable for a starting
 * point that is only ever approximately right for one gesture.
 */
internal fun brightnessFromSetting(setting: Int): Float =
    (setting / SYSTEM_BRIGHTNESS_RANGE).coerceIn(MIN_BRIGHTNESS, 1f)

/**
 * The stream step for a `0..1` level, out of [maxIndex] steps.
 *
 * The media stream has a handful of discrete steps, so a smooth drag has to
 * land on one of them. Rounded rather than truncated so that dragging to the
 * very top reaches the top instead of stopping one short.
 */
internal fun volumeIndexFor(level: Float, maxIndex: Int): Int =
    if (maxIndex <= 0) 0 else (level.coerceIn(0f, 1f) * maxIndex).roundToInt().coerceIn(0, maxIndex)

/** The `0..1` level a stream step corresponds to. The inverse of [volumeIndexFor]. */
internal fun levelForIndex(index: Int, maxIndex: Int): Float =
    if (maxIndex <= 0) 0f else (index.toFloat() / maxIndex).coerceIn(0f, 1f)
