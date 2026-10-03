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
        assertNull(scrollThumb(total = 0, firstIndex = 0, firstItemProgress = 0f, visibleItems = 0f))
    }

    @Test
    fun `a list that fits on screen has no thumb`() {
        // Every item visible: the thumb would fill the track and could not move.
        assertNull(scrollThumb(total = 8, firstIndex = 0, firstItemProgress = 0f, visibleItems = 8f))
    }

    @Test
    fun `a list with one item to spare still has a thumb`() {
        val thumb = scrollThumb(total = 9, firstIndex = 0, firstItemProgress = 0f, visibleItems = 8f)
        assertEquals(1, thumb?.scrollableItems)
    }

    // ------------------------------------------------------------------ where
    // and how long

    @Test
    fun `the thumb covers the visible share of the list`() {
        val thumb = scrollThumb(total = 100, firstIndex = 0, firstItemProgress = 0f, visibleItems = 10f)
        assertEquals(0.1f, thumb!!.sizeFraction, 0.0001f)
    }

    @Test
    fun `the top of the list is the top of the travel`() {
        val thumb = scrollThumb(total = 100, firstIndex = 0, firstItemProgress = 0f, visibleItems = 10f)
        assertEquals(0f, thumb!!.progress, 0.0001f)
    }

    @Test
    fun `the bottom of the list is the end of the travel`() {
        // 90 is the last index that leaves a full screen of items below it, so
        // the list is scrolled as far as it goes.
        val thumb = scrollThumb(total = 100, firstIndex = 90, firstItemProgress = 0f, visibleItems = 10f)
        assertEquals(1f, thumb!!.progress, 0.0001f)
    }

    @Test
    fun `halfway through the items is halfway along the travel`() {
        val thumb = scrollThumb(total = 100, firstIndex = 45, firstItemProgress = 0f, visibleItems = 10f)
        assertEquals(0.5f, thumb!!.progress, 0.0001f)
    }

    @Test
    fun `part of the way through the first item counts`() {
        val whole = scrollThumb(total = 100, firstIndex = 45, firstItemProgress = 0f, visibleItems = 10f)
        val part = scrollThumb(total = 100, firstIndex = 45, firstItemProgress = 0.5f, visibleItems = 10f)
        assertEquals(true, part!!.progress > whole!!.progress)
    }

    @Test
    fun `a thumb is never longer than its track`() {
        // Guards the clamp: visibleCount can exceed the total for a frame while
        // a list is being replaced, and a fraction above 1 would draw outside.
        val thumb = scrollThumb(total = 5, firstIndex = 0, firstItemProgress = 0f, visibleItems = 9f)
        assertNull(thumb)
    }

    // ------------------------------------------------------------------- drag

    @Test
    fun `dragging to the very top is the first item`() {
        val thumb = scrollThumb(total = 100, firstIndex = 50, firstItemProgress = 0f, visibleItems = 10f)!!
        assertEquals(0, scrollIndexAt(0f, thumb))
    }

    @Test
    fun `dragging to the very bottom is the last scrollable item`() {
        val thumb = scrollThumb(total = 100, firstIndex = 50, firstItemProgress = 0f, visibleItems = 10f)!!
        assertEquals(90, scrollIndexAt(1f, thumb))
    }

    @Test
    fun `a drag past either end stays inside the list`() {
        val thumb = scrollThumb(total = 100, firstIndex = 50, firstItemProgress = 0f, visibleItems = 10f)!!
        assertEquals(0, scrollIndexAt(-3f, thumb))
        assertEquals(90, scrollIndexAt(4f, thumb))
    }

    @Test
    fun `a short drag lands partway down`() {
        val thumb = scrollThumb(total = 100, firstIndex = 0, firstItemProgress = 0f, visibleItems = 10f)!!
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

    // ---------------------------------------------------------------------
    // How many items fit on screen, which is what the thumb's length is made of

    /**
     * The regression this was written for.
     *
     * Nine rows of 100px in a 900px window and ten of them are the *same*
     * picture on screen — the tenth is only just showing. Counting them says 9
     * against 10, which is an 11% swing in the thumb's length, and it alternates
     * several times a second the whole way down a list: that is the pulsing.
     * Measured on a folder of sixty, where the count ran 9, 10, 9, 10.
     */
    @Test
    fun `the same rows at a different count are the same length`() {
        val nine = visibleItemsIn(viewportPx = 900, itemExtents = List(9) { 100 }, itemsPerRow = 1)
        val ten = visibleItemsIn(viewportPx = 900, itemExtents = List(10) { 100 }, itemsPerRow = 1)

        assertEquals(9f, nine, 0.0001f)
        assertEquals(nine, ten, 0.0001f)
    }

    @Test
    fun `a grid counts whole rows of tiles`() {
        // 900px of 100px-tall tiles is nine rows; three across makes 27 tiles.
        // Not 9 — the height alone says how many *rows* fit, and a grid shows
        // that many times its column count.
        assertEquals(27f, visibleItemsIn(900, List(6) { 100 }, itemsPerRow = 3), 0.0001f)
    }

    @Test
    fun `rows of different heights are averaged`() {
        // 100 and 300 average 200, so 900px holds four and a half of them.
        assertEquals(4.5f, visibleItemsIn(900, listOf(100, 300), itemsPerRow = 1), 0.0001f)
    }

    @Test
    fun `nothing measured yet is nothing visible`() {
        // Before the first layout there are no sizes, and drawing nothing is the
        // right answer for a list that has not been measured.
        assertEquals(0f, visibleItemsIn(900, emptyList(), itemsPerRow = 1), 0.0001f)
        assertEquals(0f, visibleItemsIn(900, List(3) { 100 }, itemsPerRow = 0), 0.0001f)
    }

    @Test
    fun `rows of no height fall back to counting them`() {
        // A guard against dividing by the average, which would be zero.
        assertEquals(4f, visibleItemsIn(900, List(4) { 0 }, itemsPerRow = 1), 0.0001f)
    }
}
