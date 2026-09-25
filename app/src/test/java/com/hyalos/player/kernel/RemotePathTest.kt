package com.hyalos.player.kernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemotePathTest {
    @Test
    fun `normalize collapses separators and keeps a leading one`() {
        assertEquals("/", RemotePath.normalize(""))
        assertEquals("/", RemotePath.normalize("//"))
        assertEquals("/a/b", RemotePath.normalize("a//b/"))
    }

    @Test
    fun `join at the root does not double the separator`() {
        assertEquals("/movies", RemotePath.join("/", "movies"))
        assertEquals("/movies/a.mkv", RemotePath.join("/movies", "a.mkv"))
    }

    @Test
    fun `names are joined verbatim`() {
        // Spaces, CJK and punctuation pass through untouched — the server's own
        // spelling is the only one guaranteed to resolve.
        assertEquals("/影片/第 1 集 #1.mkv", RemotePath.join("/影片", "第 1 集 #1.mkv"))
    }

    @Test
    fun `parent walks up to the root and stops`() {
        assertEquals("/a", RemotePath.parent("/a/b"))
        assertEquals("/", RemotePath.parent("/a"))
        assertNull(RemotePath.parent("/"))
    }

    @Test
    fun `name is the last component`() {
        assertEquals("b.mkv", RemotePath.name("/a/b.mkv"))
        assertEquals("", RemotePath.name("/"))
    }

    @Test
    fun `ancestors run from the root to the path itself`() {
        assertEquals(listOf("/"), RemotePath.ancestors("/"))
        assertEquals(listOf("/", "/a", "/a/b"), RemotePath.ancestors("/a/b"))
    }
}
