package com.hyalos.player.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the clipboard remembers, and what it forgets.
 *
 * Small, but every rule here is one the user can see: whether the top bar's
 * paste button is there at all, and whether pressing it twice pastes twice.
 * The two rules worth pinning are that consuming is unconditional — it stopped
 * being mode-dependent, which is exactly the kind of thing that quietly comes
 * back — and that copying an empty selection is not an instruction to forget
 * what is already held.
 */
class FileClipboardTest {

    private val clipboard = FileClipboard()

    private fun item(name: String, server: String = "nas") = RemoteItem(
        serverId = server,
        path = "/movies/$name",
        name = name,
        isDirectory = false,
    )

    @Test
    fun nothing_copied_yet_is_empty() {
        assertNull(clipboard.content.value)
    }

    @Test
    fun copy_remembers_the_items_and_that_it_was_a_copy() {
        val film = item("film.mkv")
        clipboard.copy(listOf(film))

        val content = clipboard.content.value!!
        assertEquals(ClipboardMode.COPY, content.mode)
        assertEquals(listOf(film), content.items)
    }

    @Test
    fun cut_remembers_the_items_and_that_it_was_a_cut() {
        val film = item("film.mkv")
        clipboard.cut(listOf(film))

        assertEquals(ClipboardMode.CUT, clipboard.content.value!!.mode)
    }

    /** Where each item came from has to survive: the paste may land on another server. */
    @Test
    fun items_keep_their_order_and_their_server() {
        val first = item("a.mkv", server = "nas")
        val second = item("b.mkv", server = "other-nas")
        clipboard.copy(listOf(first, second))

        val items = clipboard.content.value!!.items
        assertEquals(listOf("nas", "other-nas"), items.map { it.serverId })
        assertEquals(listOf("a.mkv", "b.mkv"), items.map { it.name })
    }

    /**
     * An empty selection is not a paste instruction.
     *
     * `copy` and `cut` are reached from a selection that has just been made, so
     * an empty list means nothing was selected — not "forget the clipboard".
     * Wiping it here would make the paste button vanish for a reason the user
     * never asked for.
     */
    @Test
    fun copying_nothing_leaves_what_is_already_held() {
        clipboard.cut(listOf(item("film.mkv")))
        clipboard.copy(emptyList())

        assertEquals(ClipboardMode.CUT, clipboard.content.value!!.mode)
    }

    @Test
    fun cutting_nothing_leaves_what_is_already_held() {
        clipboard.copy(listOf(item("film.mkv")))
        clipboard.cut(emptyList())

        assertEquals(ClipboardMode.COPY, clipboard.content.value!!.mode)
    }

    /**
     * Both modes are emptied by a paste.
     *
     * A copy used to survive, so that one copy could be pasted into several
     * folders. The rule is gone deliberately: the paste button then never went
     * away and there was no way to dismiss it.
     */
    @Test
    fun pasting_a_copy_empties_it() {
        clipboard.copy(listOf(item("film.mkv")))
        clipboard.consume()

        assertNull(clipboard.content.value)
    }

    @Test
    fun pasting_a_cut_empties_it() {
        clipboard.cut(listOf(item("film.mkv")))
        clipboard.consume()

        assertNull(clipboard.content.value)
    }

    /**
     * Consuming is unconditional, including when the paste failed.
     *
     * A failed paste reports what it managed to do, and a partly-done cut has
     * already moved some of what the clipboard holds — keeping it for a retry
     * would offer to paste items that are no longer at their source.
     */
    @Test
    fun consuming_again_is_harmless() {
        clipboard.copy(listOf(item("film.mkv")))
        clipboard.consume()
        clipboard.consume()

        assertNull(clipboard.content.value)
    }

    @Test
    fun consuming_an_empty_clipboard_is_harmless() {
        clipboard.consume()

        assertNull(clipboard.content.value)
    }

    @Test
    fun clear_empties_it() {
        clipboard.cut(listOf(item("film.mkv")))
        clipboard.clear()

        assertNull(clipboard.content.value)
    }
}
