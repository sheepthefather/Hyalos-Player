package com.hyalos.player.ui.browser

import com.hyalos.player.data.SortKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.krystallos_ffi.DirEntry
import uniffi.krystallos_ffi.EntryMetadata
import uniffi.krystallos_ffi.Kind

class EntrySortingTest {
    private fun entry(
        name: String,
        kind: Kind = Kind.FILE,
        len: Long = 10,
        modifiedMs: Long? = null,
    ) = DirEntry(
        name,
        EntryMetadata(kind, len.toULong(), modifiedMs?.toULong(), null, null, false),
    )

    private fun prepare(
        vararg entries: DirEntry,
        key: SortKey = SortKey.NAME,
        ascending: Boolean = true,
    ) = EntrySorting.prepare(entries.toList(), key, ascending, naturalOrder())

    private fun names(vararg entries: DirEntry, key: SortKey = SortKey.NAME, ascending: Boolean = true) =
        prepare(*entries, key = key, ascending = ascending).map { it.name }

    @Test
    fun `directories come first, then names in order`() {
        val items = prepare(entry("b.mkv"), entry("z", Kind.DIRECTORY), entry("a.mkv"), entry("c", Kind.DIRECTORY))
        assertEquals(listOf("c", "z", "a.mkv", "b.mkv"), items.map { it.name })
    }

    @Test
    fun `by size, smallest first, and descending flips it`() {
        val small = entry("small.mkv", len = 10)
        val large = entry("large.mkv", len = 9_000)
        val middle = entry("middle.mkv", len = 500)

        assertEquals(listOf("small.mkv", "middle.mkv", "large.mkv"), names(small, large, middle, key = SortKey.SIZE))
        assertEquals(listOf("large.mkv", "middle.mkv", "small.mkv"), names(small, large, middle, key = SortKey.SIZE, ascending = false))
    }

    @Test
    fun `by date, oldest first, and descending flips it`() {
        val old = entry("old.mkv", modifiedMs = 1_000)
        val recent = entry("recent.mkv", modifiedMs = 9_000)
        val middle = entry("middle.mkv", modifiedMs = 5_000)

        assertEquals(listOf("old.mkv", "middle.mkv", "recent.mkv"), names(old, recent, middle, key = SortKey.DATE))
        assertEquals(listOf("recent.mkv", "middle.mkv", "old.mkv"), names(old, recent, middle, key = SortKey.DATE, ascending = false))
    }

    @Test
    fun `files with no date sink, in either direction`() {
        // SMB reports no timestamp as zero, which becomes null here. Sorting
        // those as if they were 1970 would sprinkle them through the list.
        val dated = entry("dated.mkv", modifiedMs = 5_000)
        val undated = entry("undated.mkv", modifiedMs = null)
        val later = entry("later.mkv", modifiedMs = 9_000)

        assertEquals(listOf("dated.mkv", "later.mkv", "undated.mkv"), names(dated, undated, later, key = SortKey.DATE))
        assertEquals(listOf("undated.mkv", "later.mkv", "dated.mkv"), names(dated, undated, later, key = SortKey.DATE, ascending = false))
    }

    @Test
    fun `by type, grouping extensions together`() {
        // The point of sorting by type in a media browser: all the mkv in one run.
        assertEquals(
            listOf("a.avi", "b.mkv", "c.mkv", "d.mp4"),
            names(entry("b.mkv"), entry("d.mp4"), entry("c.mkv"), entry("a.avi"), key = SortKey.TYPE),
        )
    }

    @Test
    fun `type ignores case and files without an extension group together`() {
        assertEquals(
            listOf("noext", "a.MKV", "b.mkv"),
            names(entry("b.mkv"), entry("noext"), entry("a.MKV"), key = SortKey.TYPE),
        )
    }

    @Test
    fun `directories stay on top whatever the order`() {
        // Sorting strictly by size would bury a folder among the big files, and
        // folders are what a browser is mostly for.
        val items = prepare(
            entry("huge.mkv", len = 9_000),
            entry("tiny.mkv", len = 1),
            entry("folder", kind = Kind.DIRECTORY),
            key = SortKey.SIZE,
            ascending = false,
        )
        assertEquals("folder", items.first().name)
    }

    @Test
    fun `ties fall back to the name order`() {
        // A directory of same-sized files must not shuffle between listings.
        assertEquals(
            listOf("a.mkv", "b.mkv", "c.mkv"),
            names(entry("c.mkv", len = 5), entry("a.mkv", len = 5), entry("b.mkv", len = 5), key = SortKey.SIZE),
        )
    }

    @Test
    fun `descending reverses the tie-break too`() {
        assertEquals(
            listOf("c.mkv", "b.mkv", "a.mkv"),
            names(entry("a.mkv", len = 5), entry("b.mkv", len = 5), entry("c.mkv", len = 5), key = SortKey.SIZE, ascending = false),
        )
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
    fun `a playlist holds only playable files`() {
        // Directories, text and images are things the browser shows and the
        // player must not try to open.
        val items = EntrySorting.playableInOrder(
            listOf(
                entry("b.mkv"),
                entry("notes.txt"),
                entry("folder", Kind.DIRECTORY),
                entry("a.mp4"),
                entry("cover.jpg"),
            ),
            nameOrder = naturalOrder(),
        )
        assertEquals(listOf("a.mp4", "b.mkv"), items.map { it.name })
    }

    @Test
    fun `a playlist follows the order the browser shows`() {
        // "Next" has to be the next one the user saw. A playlist that sorted
        // differently from the listing would play films out of order.
        val entries = listOf(entry("small.mkv", len = 10), entry("large.mkv", len = 9_000))

        assertEquals(
            listOf("small.mkv", "large.mkv"),
            EntrySorting.playableInOrder(entries, SortKey.SIZE, true, naturalOrder()).map { it.name },
        )
        assertEquals(
            listOf("large.mkv", "small.mkv"),
            EntrySorting.playableInOrder(entries, SortKey.SIZE, false, naturalOrder()).map { it.name },
        )
    }

    @Test
    fun `audio is part of the playlist`() {
        // The player handles both, and the browser marks both playable, so the
        // playlist must agree with the browser rather than with "video only".
        val items = EntrySorting.playableInOrder(
            listOf(entry("song.flac"), entry("a.mkv")),
            nameOrder = naturalOrder(),
        )
        assertEquals(listOf("a.mkv", "song.flac"), items.map { it.name })
    }

    @Test
    fun `a symlink is judged by its name, a device is never playable`() {
        val items = prepare(entry("link.mkv", Kind.SYMLINK), entry("pipe.mkv", Kind.OTHER))
        assertTrue(items.single { it.name == "link.mkv" }.playable)
        assertFalse(items.single { it.name == "pipe.mkv" }.playable)
    }
}
