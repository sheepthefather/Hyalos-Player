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
