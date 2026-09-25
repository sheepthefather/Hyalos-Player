package com.hyalos.player.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionTest {
    @Test
    fun `selecting turns the mode on`() {
        val selection = Selection.NONE.select("a.mkv")
        assertTrue(selection.active)
        assertEquals(setOf("a.mkv"), selection.ids)
    }

    @Test
    fun `toggling the last one off leaves the mode`() {
        val selection = Selection.NONE.select("a.mkv").toggle("a.mkv")
        assertFalse("nothing is selected, so the toolbar should be gone", selection.active)
        assertTrue(selection.ids.isEmpty())
    }

    @Test
    fun `toggling one of several keeps the mode`() {
        val selection = Selection.NONE.select("a").select("b").toggle("a")
        assertTrue(selection.active)
        assertEquals(setOf("b"), selection.ids)
    }

    @Test
    fun `a renamed file does not stay selected under its old name`() {
        // The bug this type exists for: after a rename the old name is no longer
        // in the listing, and a selection holding it made the toolbar say
        // "1 selected" over a list where nothing looked selected.
        val selection = Selection.NONE.select("notes.txt")

        val pruned = selection.prune(setOf("renamed.txt", "movies"))

        assertFalse("an empty selection must not keep the mode on", pruned.active)
        assertTrue(pruned.ids.isEmpty())
    }

    @Test
    fun `pruning keeps the ones that are still there`() {
        val selection = Selection.NONE.select("a").select("b").select("c")

        val pruned = selection.prune(setOf("a", "c", "d"))

        assertEquals(setOf("a", "c"), pruned.ids)
        assertTrue(pruned.active)
    }

    @Test
    fun `pruning an unchanged list changes nothing`() {
        val selection = Selection.NONE.select("a").select("b")
        assertEquals(selection, selection.prune(setOf("a", "b", "c")))
    }

    @Test
    fun `an empty selection stays empty through a prune`() {
        assertEquals(Selection.NONE, Selection.NONE.prune(setOf("a")))
    }

    @Test
    fun `select-all takes everything it is given`() {
        val selection = Selection.all(listOf("/a", "/b"))

        assertTrue(selection.active)
        assertEquals(setOf("/a", "/b"), selection.ids)
    }

    @Test
    fun `select-all with nothing to select stays out of the mode`() {
        // Otherwise the toolbar would appear over an empty list with a delete
        // button that has nothing to delete.
        assertFalse(Selection.all(emptyList()).active)
    }
}
