package com.hyalos.player.files

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import uniffi.krystallos_ffi.EntryMetadata
import uniffi.krystallos_ffi.KernelException
import uniffi.krystallos_ffi.OpenFlags
import java.io.IOException

/**
 * Reading a file for the editor, and putting it back.
 *
 * The save is the part worth testing without a server: what it does in what
 * order, and what it leaves behind when each step fails, is exactly the part
 * that cannot be checked by looking at a successful save.
 */
class TextFileStoreTest {

    /**
     * A tree of paths with bytes, failure injection and an operation log.
     *
     * Renames refuse to replace an existing name, which is the **stricter** of
     * the two backends — SMB behaves this way and the local one does not. A save
     * that works here works on both.
     */
    private class FakeSession : FileSession {
        val nodes = linkedMapOf<String, Boolean>()
        val contents = mutableMapOf<String, ByteArray>()
        val log = mutableListOf<String>()

        val failingWrite = mutableSetOf<String>()
        val failingRemove = mutableSetOf<String>()
        val failingRenameFrom = mutableSetOf<String>()
        val failingRenameTo = mutableSetOf<String>()

        /** How many writes return less than they were offered. */
        var shortWrites = 0

        /** A stat that lies about the size, for a file that grew since it was measured. */
        var declaredLength: Long? = null

        fun put(path: String, text: String) {
            nodes[path] = false
            contents[path] = text.toByteArray()
        }

        fun textAt(path: String): String? = contents[path]?.toString(Charsets.UTF_8)

        override suspend fun list(path: String) = emptyList<DirectoryEntry>()

        override suspend fun stat(path: String): EntryMetadata {
            if (path !in nodes) throw KernelException.NotFound(path)
            return EntryMetadata(
                kind = uniffi.krystallos_ffi.Kind.FILE,
                len = (declaredLength ?: contents[path]?.size?.toLong() ?: 0L).toULong(),
                modifiedMs = null, createdMs = null, accessedMs = null, readOnly = false,
            )
        }

        override suspend fun mkdir(path: String) = Unit

        override suspend fun removeFile(path: String) {
            if (path in failingRemove) throw IOException("cannot remove $path")
            log += "rm $path"
            nodes.remove(path)
            contents.remove(path)
        }

        override suspend fun removeDir(path: String) = Unit

        override suspend fun rename(from: String, to: String) {
            if (from in failingRenameFrom) throw IOException("cannot rename from $from")
            if (to in failingRenameTo) throw IOException("cannot rename to $to")
            if (from !in nodes) throw KernelException.NotFound(from)
            // The strict backend: an existing destination is a collision.
            if (to in nodes) throw KernelException.AlreadyExists(to)
            log += "mv $from $to"
            nodes.remove(from)?.let { nodes[to] = it }
            contents.remove(from)?.let { contents[to] = it }
        }

        override suspend fun copy(from: String, to: String): ULong = 0uL

        override suspend fun open(path: String, flags: OpenFlags): FileHandle {
            if (flags.createNew && path in nodes) throw KernelException.AlreadyExists(path)
            if (path !in nodes) {
                if (!flags.create) throw KernelException.NotFound(path)
                nodes[path] = false
                contents[path] = ByteArray(0)
            }
            log += "open $path"
            return Handle(path)
        }

        private inner class Handle(private val path: String) : FileHandle {
            var flushed = false

            override suspend fun read(offset: ULong, len: Int): ByteArray {
                val bytes = contents[path] ?: return ByteArray(0)
                val from = offset.toInt()
                if (from >= bytes.size) return ByteArray(0)
                return bytes.copyOfRange(from, minOf(bytes.size, from + len))
            }

            override suspend fun write(offset: ULong, data: ByteArray): Int {
                if (path in failingWrite) throw IOException("cannot write $path")
                val existing = contents[path] ?: ByteArray(0)
                if (offset.toInt() != existing.size) throw IOException("out of order write to $path")
                val take = if (shortWrites > 0) {
                    shortWrites--
                    minOf(2, data.size)
                } else {
                    data.size
                }
                contents[path] = existing + data.copyOf(take)
                return take
            }

            override suspend fun flush() {
                flushed = true
                log += "flush $path"
            }

            override suspend fun release() = Unit
        }
    }

    // ---------------------------------------------------------------------
    // Reading
    // ---------------------------------------------------------------------

    @Test
    fun `a small file comes back byte for byte`() = runTest {
        val session = FakeSession()
        session.put("/a.txt", "hello\nworld")
        val result = readFileBytes(session, "/a.txt", limit = 1024)
        assertArrayEquals("hello\nworld".toByteArray(), (result as ReadResult.Bytes).value)
    }

    @Test
    fun `a file the server says is too large is never opened`() = runTest {
        val session = FakeSession()
        session.put("/big.log", "x".repeat(100))
        val result = readFileBytes(session, "/big.log", limit = 99)
        assertEquals(ReadResult.TooLarge, result)
        // The whole point of the stat: a two-gigabyte log must not be read into
        // the heap to be refused afterwards.
        assertFalse(session.log.any { it.startsWith("open") })
    }

    @Test
    fun `a file that grew after the stat is still refused`() = runTest {
        val session = FakeSession()
        session.put("/grew.log", "x".repeat(100))
        // The stat says it is tiny; the bytes say otherwise.
        session.declaredLength = 1
        assertEquals(ReadResult.TooLarge, readFileBytes(session, "/grew.log", limit = 50))
    }

    // ---------------------------------------------------------------------
    // Saving
    // ---------------------------------------------------------------------

    @Test
    fun `a save writes a temporary, flushes, and only then moves the names`() = runTest {
        val session = FakeSession()
        session.put("/a.txt", "old")
        writeTextFile(session, "/a.txt", "new".toByteArray())

        assertEquals("new", session.textAt("/a.txt"))
        val order = session.log.filterNot { it == "rm /a.txt.hyalos-old" || it.startsWith("open") }
        assertEquals(
            listOf(
                "flush /a.txt.hyalos-edit",
                "mv /a.txt /a.txt.hyalos-old",
                "mv /a.txt.hyalos-edit /a.txt",
            ),
            order,
        )
        // Nothing of ours is left behind.
        assertFalse(session.nodes.containsKey("/a.txt.hyalos-old"))
        assertFalse(session.nodes.containsKey("/a.txt.hyalos-edit"))
    }

    @Test
    fun `the original is set aside under a name that says what it is`() = runTest {
        val session = FakeSession()
        session.put("/a.txt", "old")
        // Stop the swap half way, at the rename that would put the new file in
        // place — the one moment when the original has no usable name.
        session.failingRenameFrom += "/a.txt.hyalos-edit"
        session.failingRenameTo += "/a.txt"
        try {
            writeTextFile(session, "/a.txt", "new".toByteArray())
            fail("expected a failure")
        } catch (e: TextSaveException) {
            assertEquals(SaveStage.SWAP, e.stage)
            assertEquals("/a.txt.hyalos-edit", e.temporary)
            assertEquals("/a.txt.hyalos-old", e.backup)
        }
        // Both versions are still on the server, under names that say which is which.
        assertEquals("new", session.textAt("/a.txt.hyalos-edit"))
        assertEquals("old", session.textAt("/a.txt.hyalos-old"))
    }

    @Test
    fun `a failed write leaves the original untouched and no temporary`() = runTest {
        val session = FakeSession()
        session.put("/a.txt", "old")
        session.failingWrite += "/a.txt.hyalos-edit"
        try {
            writeTextFile(session, "/a.txt", "new".toByteArray())
            fail("expected a failure")
        } catch (e: TextSaveException) {
            assertEquals(SaveStage.WRITE, e.stage)
            assertNull(e.backup)
        }
        assertEquals("old", session.textAt("/a.txt"))
        assertFalse(session.nodes.containsKey("/a.txt.hyalos-edit"))
    }

    @Test
    fun `an original that cannot be set aside is not touched at all`() = runTest {
        val session = FakeSession()
        session.put("/a.txt", "old")
        session.failingRenameFrom += "/a.txt"
        try {
            writeTextFile(session, "/a.txt", "new".toByteArray())
            fail("expected a failure")
        } catch (e: TextSaveException) {
            assertEquals(SaveStage.WRITE, e.stage)
        }
        assertEquals("old", session.textAt("/a.txt"))
        assertFalse(session.nodes.containsKey("/a.txt.hyalos-edit"))
    }

    @Test
    fun `a swap that fails is undone when the original can go back`() = runTest {
        val session = FakeSession()
        session.put("/a.txt", "old")
        session.failingRenameFrom += "/a.txt.hyalos-edit"
        try {
            writeTextFile(session, "/a.txt", "new".toByteArray())
            fail("expected a failure")
        } catch (e: TextSaveException) {
            // Reported as "nothing changed", because nothing did.
            assertEquals(SaveStage.WRITE, e.stage)
        }
        assertEquals("old", session.textAt("/a.txt"))
        assertNull(session.textAt("/a.txt.hyalos-edit"))
        assertNull(session.textAt("/a.txt.hyalos-old"))
    }

    @Test
    fun `a leftover temporary does not block the next save`() = runTest {
        val session = FakeSession()
        session.put("/a.txt", "old")
        session.put("/a.txt.hyalos-edit", "half written")
        writeTextFile(session, "/a.txt", "new".toByteArray())
        assertEquals("new", session.textAt("/a.txt"))
        assertFalse(session.nodes.containsKey("/a.txt.hyalos-edit"))
    }

    @Test
    fun `a short write is retried until the whole file is on the server`() = runTest {
        val session = FakeSession()
        session.put("/a.txt", "old")
        session.shortWrites = 4
        val content = "0123456789"
        writeTextFile(session, "/a.txt", content.toByteArray())
        assertEquals(content, session.textAt("/a.txt"))
    }

    @Test
    fun `empty text saves an empty file`() = runTest {
        val session = FakeSession()
        session.put("/a.txt", "old")
        writeTextFile(session, "/a.txt", ByteArray(0))
        assertEquals(0, session.contents["/a.txt"]?.size)
    }

    @Test
    fun `a save flushes before the handle is released`() = runTest {
        // A save that reports success before the bytes leave the buffer is a
        // save that can lose them; the log is the only place that shows the
        // flush happened at all.
        val session = FakeSession()
        session.put("/a.txt", "old")
        writeTextFile(session, "/a.txt", "new".toByteArray())
        assertTrue(session.log.any { it == "flush /a.txt.hyalos-edit" })
    }
}
