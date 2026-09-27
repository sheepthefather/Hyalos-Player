package com.hyalos.player.ui.common

import kotlin.math.roundToInt

/**
 * Where the scrollbar's thumb sits and how long it is.
 *
 * Everything is a fraction of the track, so the arithmetic can be reasoned about
 * — and tested — without a screen. The composable turns these into pixels;
 * nothing here knows how tall anything is.
 *
 * Counts rather than pixel heights, which is an approximation: it assumes every
 * row is about as tall as every other. For this app's lists that is true by
 * construction — a row is one line of text beside a 36dp frame, a tile is a
 * 16:9 frame — and the alternative means summing the heights of every item,
 * most of which are nowhere near the screen.
 */
internal data class ScrollThumb(
    /** How much of the track the thumb covers, 0..1. */
    val sizeFraction: Float,
    /** How far through the scrollable range the list is, 0..1. */
    val progress: Float,
    /** How many items that whole range spans — which is what a drag maps onto. */
    val scrollableItems: Int,
)

/**
 * The thumb for a list showing [visibleCount] of [total] items, scrolled to
 * [firstIndex] plus [firstItemProgress] of the way through that item.
 *
 * Null when there is nothing to say: nothing to show, or everything already on
 * screen. A full-length thumb against a full-length track is a scrollbar with
 * nowhere to go, and drawing it only invites a grab that does nothing.
 */
internal fun scrollThumb(
    total: Int,
    firstIndex: Int,
    firstItemProgress: Float,
    visibleCount: Int,
): ScrollThumb? {
    val scrollableItems = total - visibleCount
    if (total <= 0 || visibleCount <= 0 || scrollableItems <= 0) return null
    val scrolled = (firstIndex + firstItemProgress).coerceIn(0f, scrollableItems.toFloat())
    return ScrollThumb(
        sizeFraction = (visibleCount.toFloat() / total).coerceIn(0f, 1f),
        progress = scrolled / scrollableItems,
        scrollableItems = scrollableItems,
    )
}

/**
 * Which item the thumb lands on when dragged [progress] of the way along its
 * travel.
 *
 * Rounded to a whole item, because that is the finest thing a lazy list can be
 * sent to: `scrollToItem` takes an index, and inventing a pixel offset would
 * mean guessing a row height. On a list long enough to want a scrollbar, one
 * item is a fraction of a millimetre of thumb travel.
 */
internal fun scrollIndexAt(progress: Float, thumb: ScrollThumb): Int =
    (progress.coerceIn(0f, 1f) * thumb.scrollableItems).roundToInt()

/**
 * How far into the first visible item the viewport has already scrolled, 0..1.
 *
 * The offset is negative once a list has moved — the first item's top is above
 * the top of the window — which is why it is negated. A zero or negative size
 * means the item has not been measured yet, and the honest answer then is "not
 * yet scrolled into it".
 */
internal fun firstItemProgress(offset: Int, size: Int): Float =
    if (size <= 0) 0f else (-offset.toFloat() / size).coerceIn(0f, 1f)
