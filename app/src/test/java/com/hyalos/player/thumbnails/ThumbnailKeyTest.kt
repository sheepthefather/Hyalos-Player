package com.hyalos.player.thumbnails

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailKeyTest {
    private fun key(
        serverId: String = "srv",
        path: String = "/movies/a.mkv",
        size: Long? = 1024,
        modifiedMs: Long? = 1_700_000_000_000,
    ) = ThumbnailKey(serverId, path, size, modifiedMs)

    @Test
    fun `the same file always hashes the same`() {
        assertEquals(key().hash, key().hash)
    }

    @Test
    fun `a replaced file gets a new hash`() {
        // The whole point of including size and time: the same path holding a
        // different film must not keep serving the old frame.
        assertNotEquals(key().hash, key(size = 2048).hash)
        assertNotEquals(key().hash, key(modifiedMs = 1_700_000_001_000).hash)
    }

    @Test
    fun `different files do not collide`() {
        assertNotEquals(key(path = "/movies/a.mkv").hash, key(path = "/movies/b.mkv").hash)
        assertNotEquals(key(serverId = "one").hash, key(serverId = "two").hash)
    }

    @Test
    fun `fields cannot bleed into each other`() {
        // Concatenating without a separator would make these the same string.
        // NUL-separating the fields is what keeps them distinct.
        assertNotEquals(key(serverId = "a", path = "b/c").hash, key(serverId = "a/b", path = "c").hash)
    }

    @Test
    fun `a missing size or time is distinct from a real one`() {
        assertNotEquals(key(size = null).hash, key(size = 0).hash)
        assertNotEquals(key(modifiedMs = null).hash, key(modifiedMs = 0).hash)
    }

    @Test
    fun `the hash is a safe file name`() {
        // Paths hold separators, spaces, `#` and CJK; the hash has to survive
        // being used verbatim as a file name.
        val hash = key(path = "/影片/第 1 集 #1.mkv").hash
        assertTrue("unexpected characters in $hash", hash.all { it in "0123456789abcdef" })
        assertEquals(32, hash.length)
    }
}
