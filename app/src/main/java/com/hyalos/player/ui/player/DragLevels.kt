package com.hyalos.player.ui.player

import kotlin.math.roundToInt

/**
 * What a vertical drag on the picture means, as arithmetic.
 *
 * The whole of the gesture is three questions — which half of the screen the
 * finger went down on, how far it moved, and what that makes the value — and
 * none of them need a `Composable` to answer. They live here for the same
 * reason `ScrollBarGeometry` does: the wiring is dull and the arithmetic is
 * where the mistakes are, so the arithmetic is the part that can be tested
 * without a screen.
 */

/** Which of the two things a drag on the picture adjusts. */
internal enum class DragTarget { BRIGHTNESS, VOLUME }

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
