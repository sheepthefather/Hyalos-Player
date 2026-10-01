package com.hyalos.player.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The arithmetic behind the brightness and volume drags.
 *
 * Worth pinning because every one of these rules is invisible on screen until
 * it is wrong, and several of them are wrong in ways that only show up at the
 * ends — a full-screen drag that never quite reaches full, a drag past the edge
 * that wraps to the other end, a brightness that can be taken to black.
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
}
