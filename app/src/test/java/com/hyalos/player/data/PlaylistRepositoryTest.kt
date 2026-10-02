package com.hyalos.player.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistRepositoryTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `entries are appended in the order they were added`() {
        val result = appended(listOf("/a", "/b"), listOf("/c", "/d"))
        assertEquals(listOf("/a", "/b", "/c", "/d"), result.paths)
        assertEquals(2, result.added)
    }

    @Test
    fun `an entry already in the list is not added again`() {
        val result = appended(listOf("/a", "/b"), listOf("/b", "/c"))
        assertEquals(listOf("/a", "/b", "/c"), result.paths)
        assertEquals(1, result.added)
    }

    @Test
    fun `the count is what went in, not what was asked for`() {
        // The snackbar reports this number, so claiming the entries that were
        // already there would be the report lying about what happened.
        val result = appended(listOf("/a"), listOf("/a", "/a", "/b"))
        assertEquals(1, result.added)
    }

    @Test
    fun `one gesture naming the same file twice adds it once`() {
        val result = appended(emptyList(), listOf("/a", "/a"))
        assertEquals(listOf("/a"), result.paths)
        assertEquals(1, result.added)
    }

    @Test
    fun `adding nothing leaves the list as it was`() {
        val result = appended(listOf("/a"), emptyList())
        assertEquals(listOf("/a"), result.paths)
        assertEquals(0, result.added)
    }

    @Test
    fun `the order the user chose is the order kept`() {
        // Deliberately not sorted: a hand-built queue means whatever order it
        // was put in, which is the whole difference from the folder it sits in.
        val result = appended(emptyList(), listOf("/z", "/a", "/m"))
        assertEquals(listOf("/z", "/a", "/m"), result.paths)
    }

    // ---------------------------------------------------------------------
    // Moving an entry, which is what a drag produces

    @Test
    fun `moving one entry to the right shifts the ones it passes`() {
        // a b c d -> take b out, put it back at index 2 -> a c b d. The index is
        // read against the list *after* the removal, which is the same place the
        // eye saw when the finger let go.
        assertEquals(listOf("a", "c", "b", "d"), listOf("a", "b", "c", "d").moved(1, 2))
        assertEquals(listOf("a", "c", "d", "b"), listOf("a", "b", "c", "d").moved(1, 3))
    }

    @Test
    fun `moving one entry to the left shifts the ones it passes`() {
        assertEquals(listOf("b", "a", "c", "d"), listOf("a", "b", "c", "d").moved(1, 0))
        assertEquals(listOf("c", "a", "b", "d"), listOf("a", "b", "c", "d").moved(2, 0))
    }

    @Test
    fun `moving to where it already is changes nothing`() {
        val list = listOf("a", "b", "c")
        assertEquals(list, list.moved(1, 1))
    }

    /** Both ends are real destinations, and neither may be refused. */
    @Test
    fun `the first and last places are reachable`() {
        assertEquals(listOf("d", "a", "b", "c"), listOf("a", "b", "c", "d").moved(3, 0))
        assertEquals(listOf("a", "b", "d", "c"), listOf("a", "b", "c", "d").moved(2, 3))
    }

    /**
     * An index that is not in the list is refused, not clamped.
     *
     * `size` is deliberately not accepted either: the entry being moved is still
     * in the list when the caller works out where it lands, so a destination
     * past the end means something upstream is confused.
     */
    @Test
    fun `an index outside the list is refused`() {
        val list = listOf("a", "b", "c")
        assertEquals(null, list.moved(-1, 0))
        assertEquals(null, list.moved(0, -1))
        assertEquals(null, list.moved(3, 0))
        assertEquals(null, list.moved(0, 3))
        assertEquals(null, emptyList<String>().moved(0, 0))
    }

    @Test
    fun `moving keeps the length`() {
        val list = listOf("a", "b", "c", "d")
        assertEquals(list.size, list.moved(3, 1)?.size)
    }

    @Test
    fun `playlists round-trip`() {
        val playlists = Playlists(byServer = mapOf("s1" to listOf("/a/b.mkv"), "s2" to emptyList()))
        val decoded = json.decodeFromString(Playlists.serializer(), json.encodeToString(playlists))
        assertEquals(playlists, decoded)
    }

    @Test
    fun `a file from an older version still loads`() {
        // The field is defaulted, so a document without it decodes to no lists
        // rather than failing — which the corruption handler would turn into
        // silently dropping every playlist.
        val decoded = json.decodeFromString(Playlists.serializer(), "{}")
        assertEquals(emptyMap<String, List<String>>(), decoded.byServer)
    }
}
