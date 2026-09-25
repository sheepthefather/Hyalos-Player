package com.hyalos.player.thumbnails

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ThumbnailCacheTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun cache() = ThumbnailCache(temp.newFolder("thumbs"))

    private fun key(n: Int) = ThumbnailKey("srv", "/movies/$n.mkv", 1024, 0)

    private fun bytes(n: Int) = ByteArray(n) { it.toByte() }

    @Test
    fun `bytes round-trip`() = runTest {
        val cache = cache()
        cache.putBytes(key(1), bytes(500))
        assertArrayEquals(bytes(500), cache.getBytes(key(1)))
    }

    @Test
    fun `an absent key reads as null`() = runTest {
        assertNull(cache().getBytes(key(9)))
    }

    @Test
    fun `usage counts what was written`() = runTest {
        val cache = cache()
        cache.putBytes(key(1), bytes(1000))
        cache.putBytes(key(2), bytes(2000))
        assertEquals(3000, cache.usage())
    }

    @Test
    fun `rewriting the same key does not double-count`() = runTest {
        val cache = cache()
        cache.putBytes(key(1), bytes(1000))
        cache.putBytes(key(1), bytes(400))
        assertEquals(400, cache.usage())
        assertArrayEquals(bytes(400), cache.getBytes(key(1)))
    }

    @Test
    fun `going over the limit evicts down to the low-water mark, not to the edge`() = runTest {
        // Trimming to exactly the limit would mean the very next write is over
        // again, so every write would become a delete.
        val cache = cache()
        for (i in 1..10) cache.putBytes(key(i), bytes(1000))
        cache.setLimit(5_000)

        val usage = cache.usage()
        assertTrue("expected to be under the limit, was $usage", usage <= 5_000)
        assertTrue("expected trimming past the edge, was $usage", usage <= 4_500)
    }

    @Test
    fun `reading a thumbnail stamps it as recently used`() = runTest {
        // Eviction order is file modification time, so a read has to move it.
        // Without this, "least recently used" would really be "written longest ago".
        val dir = temp.newFolder("thumbs")
        val cache = ThumbnailCache(dir)
        cache.putBytes(key(1), bytes(1000))
        val file = File(dir, "${key(1).hash}.jpg")
        file.setLastModified(1_000)

        cache.getBytes(key(1))

        assertTrue("the read did not stamp the file", file.lastModified() > 1_000)
    }

    @Test
    fun `eviction removes the least recently used, not the oldest written`() = runTest {
        // Modification times are set explicitly because three writes land in the
        // same millisecond — file timestamps have only millisecond resolution,
        // so writing them back to back would leave the order undetermined.
        // In the app, extractions are seconds apart.
        val dir = temp.newFolder("thumbs")
        val cache = ThumbnailCache(dir)
        cache.putBytes(key(1), bytes(1000))
        cache.putBytes(key(2), bytes(1000))
        cache.putBytes(key(3), bytes(1000))
        File(dir, "${key(1).hash}.jpg").setLastModified(3_000) // read most recently
        File(dir, "${key(2).hash}.jpg").setLastModified(1_000) // read longest ago
        File(dir, "${key(3).hash}.jpg").setLastModified(2_000)

        cache.setLimit(2_500)

        assertNull("the least recently used should have gone", cache.getBytes(key(2)))
        assertTrue("the most recently used was evicted", cache.getBytes(key(1)) != null)
    }

    @Test
    fun `lowering the limit evicts immediately`() = runTest {
        val cache = cache()
        for (i in 1..10) cache.putBytes(key(i), bytes(1000))
        assertEquals(10_000, cache.usage())

        cache.setLimit(3_000)

        assertTrue("usage was ${cache.usage()}", cache.usage() <= 3_000)
    }

    @Test
    fun `a zero limit disables the cache and clears what is there`() = runTest {
        val cache = cache()
        cache.putBytes(key(1), bytes(1000))
        cache.setLimit(0)

        assertTrue(cache.isDisabled)
        assertNull("nothing should be served while disabled", cache.getBytes(key(1)))
        assertEquals(0, cache.usage())

        // And nothing new is written while disabled.
        cache.putBytes(key(2), bytes(1000))
        assertEquals(0, cache.usage())
    }

    @Test
    fun `an image larger than the whole budget is refused rather than thrashed`() = runTest {
        val cache = cache()
        cache.setLimit(1_000)
        cache.putBytes(key(1), bytes(5_000))
        assertEquals(0, cache.usage())
    }

    @Test
    fun `clear empties the cache`() = runTest {
        val cache = cache()
        cache.putBytes(key(1), bytes(1000))
        cache.markMissing(key(2))

        cache.clear()

        assertEquals(0, cache.usage())
        assertNull(cache.getBytes(key(1)))
        assertFalse(cache.isMissing(key(2)))
    }

    @Test
    fun `a missing marker is remembered`() = runTest {
        val cache = cache()
        assertFalse(cache.isMissing(key(1)))
        cache.markMissing(key(1))
        assertTrue(cache.isMissing(key(1)))
    }

    @Test
    fun `writing an image clears a stale missing marker`() = runTest {
        // A file that failed once may succeed after the film is replaced.
        val cache = cache()
        cache.markMissing(key(1))
        cache.putBytes(key(1), bytes(100))
        assertFalse(cache.isMissing(key(1)))
    }

    @Test
    fun `markers are counted so they cannot accumulate forever`() = runTest {
        // They hold no image data, but they do occupy a directory entry; at zero
        // weight they would never be evicted.
        val cache = cache()
        for (i in 1..20) cache.markMissing(key(i))
        assertTrue("markers were not counted at all", cache.usage() > 0)

        cache.setLimit(5_000)
        assertTrue("markers were never evicted", cache.usage() <= 5_000)
    }

    @Test
    fun `a partially written file is never visible`() = runTest {
        // putBytes writes to a temporary name and renames, so a failure leaves
        // no half-image behind under the real name.
        val cache = cache()
        cache.putBytes(key(1), bytes(1000))
        val leftovers = temp.root.walkTopDown().filter { it.name.endsWith(".tmp") }.toList()
        assertTrue("temporary files were left behind: $leftovers", leftovers.isEmpty())
    }
}
