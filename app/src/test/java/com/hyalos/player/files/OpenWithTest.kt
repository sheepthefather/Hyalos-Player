package com.hyalos.player.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The parts of "open in another app" that are arithmetic rather than Android:
 * which type to claim a file is, what name it may be written under, and which
 * leftovers to drop. The rest is an intent and a chooser, and only a device can
 * say whether those work.
 */
class OpenWithTest {

    // ------------------------------------------------------------------ types

    /** The table exists for the extensions the platform is worst at. */
    @Test
    fun `common extensions get their own types`() {
        assertEquals("application/pdf", mimeTypeFor("剧本.pdf"))
        assertEquals("image/jpeg", mimeTypeFor("照片.JPG"))
        assertEquals("application/zip", mimeTypeFor("包.zip"))
        assertEquals("application/x-7z-compressed", mimeTypeFor("包.7z"))
        assertEquals("application/epub+zip", mimeTypeFor("书.epub"))
        assertEquals("text/plain", mimeTypeFor("笔记.txt"))
    }

    /**
     * An unknown type still gets an answer. `octet-stream` opens for almost
     * nothing, but it is the honest claim, and some archive managers accept it.
     */
    @Test
    fun `an unknown extension falls back to octet stream`() {
        assertEquals("application/octet-stream", mimeTypeFor("没有扩展名"))
        assertEquals("application/octet-stream", mimeTypeFor("奇怪.xyzzy"))
    }

    // ------------------------------------------------------------- file names

    /**
     * The name comes from a remote listing, so it is data from outside. A
     * separator in it must not become a directory on our disk.
     */
    @Test
    fun `a name with separators stays a file name`() {
        assertEquals("a_b.txt", safeFileName("a/b.txt"))
        assertEquals("a_b.txt", safeFileName("a\\b.txt"))
    }

    /** The two names that mean "a directory" in POSIX are not honoured. */
    @Test
    fun `dot entries are replaced`() {
        assertEquals("file", safeFileName("."))
        assertEquals("file", safeFileName(".."))
        assertEquals("file", safeFileName(""))
        assertEquals("file", safeFileName("   "))
    }

    // ---------------------------------------------------------------- cleanup

    @Test
    fun `downloads older than a day are dropped`() {
        val root = Files.createTempDirectory("open-with").toFile()
        val old = File(root, "old").apply { mkdirs(); File(this, "a.txt").writeText("x") }
        old.setLastModified(System.currentTimeMillis() - 2 * 24 * 60 * 60 * 1000L)
        val fresh = File(root, "fresh").apply { mkdirs(); File(this, "b.txt").writeText("y") }

        val deleted = purgeOpenCache(root, System.currentTimeMillis())

        assertEquals(1, deleted)
        assertFalse(old.exists())
        assertTrue(fresh.exists())
    }

    /** Over budget, the oldest go first — the newest is most likely in use. */
    @Test
    fun `the oldest downloads go first when over budget`() {
        val root = Files.createTempDirectory("open-with").toFile()
        val now = System.currentTimeMillis()
        val older = File(root, "older").apply {
            mkdirs()
            File(this, "a.bin").writeBytes(ByteArray(600))
        }
        val newer = File(root, "newer").apply {
            mkdirs()
            File(this, "b.bin").writeBytes(ByteArray(600))
        }
        older.setLastModified(now - 60_000)
        newer.setLastModified(now - 1_000)

        val deleted = purgeOpenCache(root, now, maxAgeMs = Long.MAX_VALUE, budgetBytes = 1000)

        assertEquals(1, deleted)
        assertFalse(older.exists())
        assertTrue(newer.exists())
    }

    /** Under budget and young: nothing is touched. */
    @Test
    fun `a small recent cache is left alone`() {
        val root = Files.createTempDirectory("open-with").toFile()
        val only = File(root, "only").apply { mkdirs(); File(this, "a.txt").writeText("x") }

        assertEquals(0, purgeOpenCache(root, System.currentTimeMillis()))
        assertTrue(only.exists())
    }
}
