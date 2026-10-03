package com.hyalos.player.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The arithmetic behind the drags on the picture: brightness, volume, and the
 * horizontal seek.
 *
 * Worth pinning because every one of these rules is invisible on screen until
 * it is wrong, and several of them are wrong in ways that only show up at the
 * ends — a full-screen drag that never quite reaches full, a drag past the edge
 * that wraps to the other end, a brightness that can be taken to black, a swipe
 * off the end of a film that wraps round to the start.
 */
class DragLevelsTest {

    @Test
    fun the_left_half_is_brightness_and_the_right_is_volume() {
        assertEquals(DragTarget.BRIGHTNESS, dragTargetAt(x = 0f, width = 1000f))
        assertEquals(DragTarget.BRIGHTNESS, dragTargetAt(x = 499f, width = 1000f))
        assertEquals(DragTarget.VOLUME, dragTargetAt(x = 501f, width = 1000f))
        assertEquals(DragTarget.VOLUME, dragTargetAt(x = 1000f, width = 1000f))
    }

    @Test
    fun the_middle_itself_counts_as_volume() {
        assertEquals(DragTarget.VOLUME, dragTargetAt(x = 500f, width = 1000f))
    }

    @Test
    fun dragging_up_raises_the_value() {
        assertEquals(0.6f, levelAfter(start = 0.5f, dragPx = -100f, heightPx = 1000f), 1e-6f)
    }

    @Test
    fun dragging_down_lowers_it() {
        assertEquals(0.4f, levelAfter(start = 0.5f, dragPx = 100f, heightPx = 1000f), 1e-6f)
    }

    @Test
    fun a_full_screen_height_is_the_whole_range() {
        assertEquals(1f, levelAfter(start = 0f, dragPx = -1000f, heightPx = 1000f), 1e-6f)
        assertEquals(0f, levelAfter(start = 1f, dragPx = 1000f, heightPx = 1000f), 1e-6f)
    }

    /**
     * Running off the end stops at the end.
     *
     * Wrapping would be the nastier bug of the two and is the one a naive
     * modulo produces: dragging far past the bottom would come back round to
     * full brightness.
     */
    @Test
    fun dragging_past_the_end_stops_there() {
        assertEquals(1f, levelAfter(start = 0.5f, dragPx = -5000f, heightPx = 1000f), 1e-6f)
        assertEquals(0f, levelAfter(start = 0.5f, dragPx = 5000f, heightPx = 1000f), 1e-6f)
    }

    @Test
    fun a_zero_height_screen_does_not_divide_by_it() {
        assertEquals(0.5f, levelAfter(start = 0.5f, dragPx = -100f, heightPx = 0f), 1e-6f)
        assertEquals(1f, levelAfter(start = 5f, dragPx = 0f, heightPx = 0f), 1e-6f)
    }

    @Test
    fun brightness_can_be_taken_down_to_the_floor_but_no_further() {
        assertEquals(MIN_BRIGHTNESS, brightnessAfter(start = 0.5f, dragPx = 5000f, heightPx = 1000f), 1e-6f)
        // And the floor is above black, or there would be nothing left to see.
        assertEquals(true, MIN_BRIGHTNESS > 0f)
    }

    @Test
    fun brightness_still_reaches_the_top() {
        assertEquals(1f, brightnessAfter(start = 0.5f, dragPx = -5000f, heightPx = 1000f), 1e-6f)
    }

    @Test
    fun a_volume_level_lands_on_a_step() {
        assertEquals(0, volumeIndexFor(0f, maxIndex = 15))
        assertEquals(15, volumeIndexFor(1f, maxIndex = 15))
        assertEquals(8, volumeIndexFor(0.5f, maxIndex = 15))
    }

    /** The top of the range has to be reachable, or the last step is dead. */
    @Test
    fun nearly_full_still_rounds_up_to_the_last_step() {
        assertEquals(15, volumeIndexFor(0.99f, maxIndex = 15))
    }

    @Test
    fun a_level_outside_zero_to_one_is_clamped() {
        assertEquals(0, volumeIndexFor(-3f, maxIndex = 15))
        assertEquals(15, volumeIndexFor(3f, maxIndex = 15))
    }

    @Test
    fun a_stream_with_no_steps_does_not_divide_by_zero() {
        assertEquals(0, volumeIndexFor(0.5f, maxIndex = 0))
        assertEquals(0f, levelForIndex(3, maxIndex = 0), 1e-6f)
    }

    /** Reading the level back out is how the drag knows where to start. */
    @Test
    fun a_step_reads_back_as_the_level_it_is() {
        assertEquals(0f, levelForIndex(0, maxIndex = 15), 1e-6f)
        assertEquals(1f, levelForIndex(15, maxIndex = 15), 1e-6f)
        assertEquals(0.5f, levelForIndex(8, maxIndex = 16), 1e-6f)
    }

    // ----------------------------------------------------------- which way it went

    /**
     * The first movement decides, and it is the *bigger* component that decides.
     *
     * A gesture read afresh every frame would hand a curved swipe to the
     * brightness half way through, which is the bug this exists to prevent.
     */
    @Test
    fun the_bigger_component_is_the_direction() {
        assertEquals(DragAxis.HORIZONTAL, axisOf(dx = 10f, dy = 2f))
        assertEquals(DragAxis.VERTICAL, axisOf(dx = 2f, dy = 10f))
        // Sign is not part of it: leftwards is still horizontal.
        assertEquals(DragAxis.HORIZONTAL, axisOf(dx = -10f, dy = 2f))
    }

    /** A tie goes to what the screen already did before there was a seek. */
    @Test
    fun a_dead_diagonal_counts_as_vertical() {
        assertEquals(DragAxis.VERTICAL, axisOf(dx = 5f, dy = 5f))
        assertEquals(DragAxis.VERTICAL, axisOf(dx = 0f, dy = 0f))
    }

    // ------------------------------------------------------------- where it started

    /**
     * The middle three fifths, and nothing else.
     *
     * The outer fifths belong to the system's own back gesture, and a swipe
     * inwards from the left edge is not a request to rewind.
     */
    @Test
    fun only_the_middle_may_seek() {
        assertEquals(false, seekZoneAt(x = 199f, width = 1000f))
        assertEquals(true, seekZoneAt(x = 200f, width = 1000f))
        assertEquals(true, seekZoneAt(x = 500f, width = 1000f))
        assertEquals(true, seekZoneAt(x = 800f, width = 1000f))
        assertEquals(false, seekZoneAt(x = 801f, width = 1000f))
    }

    @Test
    fun an_unmeasured_screen_has_no_seek_zone() {
        assertEquals(false, seekZoneAt(x = 0f, width = 0f))
    }

    // -------------------------------------------------------------- where it ended

    /**
     * A release in one of the four corners cancels the seek.
     *
     * A fifth of each side. The corners are where the control bar's own buttons
     * are, so a finger that stops there was more likely reaching for one than
     * choosing a minute.
     */
    @Test
    fun the_four_corners_cancel() {
        assertEquals(true, inCorner(x = 0f, y = 0f, width = 1000f, height = 600f))
        assertEquals(true, inCorner(x = 1000f, y = 0f, width = 1000f, height = 600f))
        assertEquals(true, inCorner(x = 0f, y = 600f, width = 1000f, height = 600f))
        assertEquals(true, inCorner(x = 1000f, y = 600f, width = 1000f, height = 600f))
    }

    /** An edge is a fifth wide **and** a fifth tall — either one alone is not a corner. */
    @Test
    fun the_middle_of_an_edge_is_not_a_corner() {
        // Along the bottom, in the middle of the screen's width.
        assertEquals(false, inCorner(x = 500f, y = 600f, width = 1000f, height = 600f))
        // Down the left, in the middle of its height.
        assertEquals(false, inCorner(x = 0f, y = 300f, width = 1000f, height = 600f))
    }

    @Test
    fun the_middle_of_the_screen_is_not_a_corner() {
        assertEquals(false, inCorner(x = 500f, y = 300f, width = 1000f, height = 600f))
    }

    /** The band's own edge is inside it, so the rule has no gap to fall through. */
    @Test
    fun the_edge_of_a_corner_is_still_in_it() {
        // 200 = a fifth of the width, 120 = a fifth of the height.
        assertEquals(true, inCorner(x = 200f, y = 120f, width = 1000f, height = 600f))
        assertEquals(false, inCorner(x = 201f, y = 121f, width = 1000f, height = 600f))
    }

    @Test
    fun an_unmeasured_screen_has_no_corners() {
        assertEquals(false, inCorner(x = 0f, y = 0f, width = 0f, height = 600f))
        assertEquals(false, inCorner(x = 0f, y = 0f, width = 1000f, height = 0f))
    }

    // ----------------------------------------------------------------- where it lands

    private val TEN_MINUTES = 600_000L

    /** A tenth of the film per screen width, so a long film and a short one match. */
    @Test
    fun a_whole_screen_across_is_a_tenth_of_the_film() {
        assertEquals(
            TEN_MINUTES / 10,
            seekTargetAfter(startMs = 0, dragPx = 1000f, widthPx = 1000f, durationMs = TEN_MINUTES),
        )
    }

    @Test
    fun half_a_screen_is_half_of_that() {
        assertEquals(
            30_000L,
            seekTargetAfter(startMs = 0, dragPx = 500f, widthPx = 1000f, durationMs = TEN_MINUTES),
        )
    }

    @Test
    fun dragging_left_goes_back() {
        assertEquals(
            30_000L,
            seekTargetAfter(startMs = 60_000, dragPx = -500f, widthPx = 1000f, durationMs = TEN_MINUTES),
        )
    }

    /**
     * Running off either end stops at that end.
     *
     * Wrapping is the one a naive modulo gives, and on a seek it is worse than
     * on a slider: dragging a little too far left from the first seconds would
     * land near the end of the film.
     */
    @Test
    fun a_seek_past_either_end_stops_there() {
        assertEquals(
            0L,
            seekTargetAfter(startMs = 1_000, dragPx = -1000f, widthPx = 1000f, durationMs = TEN_MINUTES),
        )
        assertEquals(
            TEN_MINUTES,
            seekTargetAfter(startMs = 599_000, dragPx = 1000f, widthPx = 1000f, durationMs = TEN_MINUTES),
        )
    }

    /**
     * A film of unknown length does not move.
     *
     * There is no tenth of it to take, and the player reports `C.TIME_UNSET` —
     * a large negative — until it has read enough to know.
     */
    @Test
    fun a_film_of_unknown_length_does_not_seek() {
        assertEquals(
            5_000L,
            seekTargetAfter(startMs = 5_000, dragPx = 500f, widthPx = 1000f, durationMs = -1L),
        )
        assertEquals(
            5_000L,
            seekTargetAfter(startMs = 5_000, dragPx = 500f, widthPx = 1000f, durationMs = 0L),
        )
    }

    @Test
    fun an_unmeasured_screen_does_not_divide_by_it() {
        assertEquals(
            5_000L,
            seekTargetAfter(startMs = 5_000, dragPx = 500f, widthPx = 0f, durationMs = TEN_MINUTES),
        )
    }
}
