package com.hyalos.player.ui.common

import kotlin.math.roundToInt

/**
 * Where the scrollbar's thumb sits and how long it is.
 *
 * Everything is a fraction of the track, so the arithmetic can be reasoned about
 * — and tested — without a screen. The composable turns these into pixels;
 * nothing here knows how tall anything is.
 *
 * **The length comes from pixels, not from counting items**, and that is not a
 * refinement — counting them is visibly wrong. The count of items intersecting
 * a viewport is not the same number from one frame to the next: an item leaving
 * the top takes it down by one, an item arriving at the bottom puts it back. A
 * thumb whose length is that count divided by the total therefore pulses the
 * whole way down a list — measured at 9/60 against 10/60 on a folder of sixty,
 * alternating several times a second, which is 11% of its length.
 *
 * So what is passed in is how many items *would* fit, as a fraction: the
 * viewport's height over the average item's. With rows of a height it is a
 * constant, and the thumb holds still.
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
    visibleItems: Float,
): ScrollThumb? {
    val scrollableItems = total - visibleItems
    if (total <= 0 || visibleItems <= 0f || scrollableItems <= 0f) return null
    val scrolled = (firstIndex + firstItemProgress).coerceIn(0f, scrollableItems)
    return ScrollThumb(
        sizeFraction = (visibleItems / total).coerceIn(0f, 1f),
        progress = scrolled / scrollableItems,
        // Rounded because a drag lands on an index, and this is the far end of
        // that mapping — see [scrollIndexAt].
        scrollableItems = scrollableItems.roundToInt(),
    )
}

/**
 * How many items fit on screen, as a fraction — from pixels, not by counting.
 *
 * [itemExtents] are the main-axis sizes of the items currently visible; their
 * average is used as the size of a typical one. **The average is the point**:
 * counting the visible items gives a number that alternates as items arrive and
 * leave, while the average of their sizes does not — which is what stops the
 * thumb pulsing (see the note on [ScrollThumb]).
 *
 * [itemsPerRow] is 1 for a list and the column count for a grid, where "how many
 * items fit" is rows × columns. Getting it wrong in a grid would not jitter, it
 * would simply make the thumb the wrong length by that factor.
 *
 * Falls back to the plain count when the average is unusable — before the first
 * layout there are no sizes at all, and a count of zero there draws nothing,
 * which is the right thing for a list that has not been measured yet.
 */
internal fun visibleItemsIn(
    viewportPx: Int,
    itemExtents: List<Int>,
    itemsPerRow: Int,
): Float {
    if (itemExtents.isEmpty() || itemsPerRow <= 0) return 0f
    val average = itemExtents.sum().toFloat() / itemExtents.size
    if (average <= 0f) return itemExtents.size.toFloat()
    return viewportPx.toFloat() / average * itemsPerRow
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
