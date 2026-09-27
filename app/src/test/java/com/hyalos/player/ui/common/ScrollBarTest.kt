package com.hyalos.player.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The scrollbar's arithmetic, which is the part of it that can be wrong without
 * anything on screen looking obviously broken — a thumb that is slightly the
 * wrong length just feels off.
 */
class ScrollBarTest {

    // ---------------------------------------------------------------- nothing
    // to say

    @Test
    fun `an empty list has no thumb`() {
        assertNull(scrollThumb(total = 0, firstIndex = 0, firstItemProgress = 0f, visibleCount = 0))
    }

    @Test
    fun `a list that fits on screen has no thumb`() {
        // Every item visible: the thumb would fill the track and could not move.
        assertNull(scrollThumb(total = 8, firstIndex = 0, firstItemProgress = 0f, visibleCount = 8))
    }

    @Test
    fun `a list with one item to spare still has a thumb`() {
        val thumb = scrollThumb(total = 9, firstIndex = 0, firstItemProgress = 0f, visibleCount = 8)
        assertEquals(1, thumb?.scrollableItems)
    }

    // ------------------------------------------------------------------ where
    // and how long

    @Test
    fun `the thumb covers the visible share of the list`() {
        val thumb = scrollThumb(total = 100, firstIndex = 0, firstItemProgress = 0f, visibleCount = 10)
        assertEquals(0.1f, thumb!!.sizeFraction, 0.0001f)
    }

    @Test
    fun `the top of the list is the top of the travel`() {
        val thumb = scrollThumb(total = 100, firstIndex = 0, firstItemProgress = 0f, visibleCount = 10)
        assertEquals(0f, thumb!!.progress, 0.0001f)
    }

    @Test
    fun `the bottom of the list is the end of the travel`() {
        // 90 is the last index that leaves a full screen of items below it, so
        // the list is scrolled as far as it goes.
        val thumb = scrollThumb(total = 100, firstIndex = 90, firstItemProgress = 0f, visibleCount = 10)
        assertEquals(1f, thumb!!.progress, 0.0001f)
    }

    @Test
    fun `halfway through the items is halfway along the travel`() {
        val thumb = scrollThumb(total = 100, firstIndex = 45, firstItemProgress = 0f, visibleCount = 10)
        assertEquals(0.5f, thumb!!.progress, 0.0001f)
    }

    @Test
    fun `part of the way through the first item counts`() {
        val whole = scrollThumb(total = 100, firstIndex = 45, firstItemProgress = 0f, visibleCount = 10)
        val part = scrollThumb(total = 100, firstIndex = 45, firstItemProgress = 0.5f, visibleCount = 10)
        assertEquals(true, part!!.progress > whole!!.progress)
    }

    @Test
    fun `a thumb is never longer than its track`() {
        // Guards the clamp: visibleCount can exceed the total for a frame while
        // a list is being replaced, and a fraction above 1 would draw outside.
        val thumb = scrollThumb(total = 5, firstIndex = 0, firstItemProgress = 0f, visibleCount = 9)
        assertNull(thumb)
    }

    // ------------------------------------------------------------------- drag

    @Test
    fun `dragging to the very top is the first item`() {
        val thumb = scrollThumb(total = 100, firstIndex = 50, firstItemProgress = 0f, visibleCount = 10)!!
        assertEquals(0, scrollIndexAt(0f, thumb))
    }

    @Test
    fun `dragging to the very bottom is the last scrollable item`() {
        val thumb = scrollThumb(total = 100, firstIndex = 50, firstItemProgress = 0f, visibleCount = 10)!!
        assertEquals(90, scrollIndexAt(1f, thumb))
    }

    @Test
    fun `a drag past either end stays inside the list`() {
        val thumb = scrollThumb(total = 100, firstIndex = 50, firstItemProgress = 0f, visibleCount = 10)!!
        assertEquals(0, scrollIndexAt(-3f, thumb))
        assertEquals(90, scrollIndexAt(4f, thumb))
    }

    @Test
    fun `a short drag lands partway down`() {
        val thumb = scrollThumb(total = 100, firstIndex = 0, firstItemProgress = 0f, visibleCount = 10)!!
        assertEquals(45, scrollIndexAt(0.5f, thumb))
    }

    // -------------------------------------------------- reading the list state

    @Test
    fun `an unscrolled list has not moved into its first item`() {
        assertEquals(0f, firstItemProgress(offset = 0, size = 200), 0.0001f)
    }

    @Test
    fun `a list scrolled halfway into its first item reports half`() {
        // The offset is negative once a list has moved: the item's top is above
        // the top of the window.
        assertEquals(0.5f, firstItemProgress(offset = -100, size = 200), 0.0001f)
    }

    @Test
    fun `an unmeasured item does not pretend to be scrolled`() {
        assertEquals(0f, firstItemProgress(offset = -100, size = 0), 0.0001f)
    }

    @Test
    fun `progress never exceeds the item`() {
        assertEquals(1f, firstItemProgress(offset = -900, size = 200), 0.0001f)
        assertEquals(0f, firstItemProgress(offset = 50, size = 200), 0.0001f)
    }
}
