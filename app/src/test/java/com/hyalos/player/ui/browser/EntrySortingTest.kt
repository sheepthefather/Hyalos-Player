package com.hyalos.player.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.krystallos_ffi.DirEntry
import uniffi.krystallos_ffi.EntryMetadata
import uniffi.krystallos_ffi.Kind

class EntrySortingTest {
    private fun entry(name: String, kind: Kind = Kind.FILE, len: Long = 10) = DirEntry(
        name,
        EntryMetadata(kind, len.toULong(), null, null, null, false),
    )

    private fun prepare(vararg entries: DirEntry) =
        EntrySorting.prepare(entries.toList(), naturalOrder())

    @Test
    fun `directories come first, then names in order`() {
        val items = prepare(entry("b.mkv"), entry("z", Kind.DIRECTORY), entry("a.mkv"), entry("c", Kind.DIRECTORY))
        assertEquals(listOf("c", "z", "a.mkv", "b.mkv"), items.map { it.name })
    }

    @Test
    fun `housekeeping entries are hidden`() {
        val items = prepare(
            entry(".DS_Store"),
            entry("\$RECYCLE.BIN", Kind.DIRECTORY),
            entry("System Volume Information", Kind.DIRECTORY),
            entry("@eaDir", Kind.DIRECTORY),
            entry("#recycle", Kind.DIRECTORY),
            entry("movie.mkv"),
        )
        assertEquals(listOf("movie.mkv"), items.map { it.name })
    }

    @Test
    fun `kind comes from the extension, case-insensitively`() {
        assertEquals(BrowserItem.Kind.VIDEO, EntrySorting.kindOf("A.MKV"))
        assertEquals(BrowserItem.Kind.AUDIO, EntrySorting.kindOf("song.Flac"))
        assertEquals(BrowserItem.Kind.OTHER, EntrySorting.kindOf("notes.txt"))
        assertEquals(BrowserItem.Kind.OTHER, EntrySorting.kindOf("no-extension"))
        // ExoPlayer cannot open these, so they must not look playable.
        assertEquals(BrowserItem.Kind.OTHER, EntrySorting.kindOf("old.rmvb"))
        assertEquals(BrowserItem.Kind.OTHER, EntrySorting.kindOf("old.wmv"))
    }

    @Test
    fun `directories carry no size and files do`() {
        val (dir, file) = prepare(entry("d", Kind.DIRECTORY, 4096), entry("f.mp4", len = 1234))
        assertNull(dir.size)
        assertEquals(1234L, file.size)
    }

    @Test
    fun `a symlink is judged by its name, a device is never playable`() {
        val items = prepare(entry("link.mkv", Kind.SYMLINK), entry("pipe.mkv", Kind.OTHER))
        assertTrue(items.single { it.name == "link.mkv" }.playable)
        assertFalse(items.single { it.name == "pipe.mkv" }.playable)
    }
}
