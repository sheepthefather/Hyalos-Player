package com.hyalos.player.files

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.krystallos_ffi.EntryMetadata
import uniffi.krystallos_ffi.KernelException
import java.io.IOException

/**
 * The half of file operations that is not I/O: which order things are removed
 * in, what a partial failure reports, and what happens when the destination is
 * already taken.
 *
 * A fake [FileSession] makes all of that reachable without a server — the same
 * reason `ReaderSource` exists for the data source.
 */
class FileOperationsTest {

    /** A tree of paths, with per-path failure injection and an operation log. */
    private class FakeSession : FileSession {
        /** path -> is a directory. */
        val nodes = linkedMapOf<String, Boolean>()
        val log = mutableListOf<String>()
        val failing = mutableSetOf<String>()

        fun dir(path: String, vararg children: String) {
            nodes[path] = true
            for (child in children) nodes["$path/$child"] = nodes["$path/$child"] ?: false
        }

        fun file(path: String) {
            nodes[path] = false
        }

        private fun childrenOf(path: String): List<DirectoryEntry> {
            val prefix = if (path == "/") "/" else "$path/"
            return nodes.entries
                .filter { it.key.startsWith(prefix) && !it.key.removePrefix(prefix).contains('/') }
                .map { (key, isDir) -> DirectoryEntry(key.removePrefix(prefix), isDir) }
        }

        override suspend fun list(path: String): List<DirectoryEntry> {
            if (path in failing) throw IOException("cannot list $path")
            log += "list $path"
            return childrenOf(path)
        }

        override suspend fun stat(path: String): EntryMetadata {
            val isDir = nodes[path] ?: throw KernelException.NotFound(path)
            return EntryMetadata(
                kind = if (isDir) uniffi.krystallos_ffi.Kind.DIRECTORY else uniffi.krystallos_ffi.Kind.FILE,
                len = 1uL, modifiedMs = null, createdMs = null, accessedMs = null, readOnly = false,
            )
        }

        override suspend fun mkdir(path: String) {
            if (path in failing) throw IOException("cannot mkdir $path")
            log += "mkdir $path"
            nodes[path] = true
        }

        override suspend fun removeFile(path: String) {
            if (path in failing) throw IOException("cannot remove $path")
            log += "rm $path"
            nodes.remove(path)
        }

        override suspend fun removeDir(path: String) {
            if (path in failing) throw IOException("cannot rmdir $path")
            log += "rmdir $path"
            nodes.remove(path)
        }

        override suspend fun rename(from: String, to: String) {
            if (from in failing) throw IOException("cannot rename $from")
            log += "mv $from $to"
            nodes.remove(from)?.let { nodes[to] = it }
        }

        override suspend fun copy(from: String, to: String): ULong {
            if (from in failing) throw IOException("cannot copy $from")
            log += "cp $from $to"
            nodes[to] = false
            return 1uL
        }
    }

    private fun operations(session: FakeSession) = FileOperations {
        FileOperations.OpenSession(session) {}
    }

    private fun item(path: String, isDirectory: Boolean = false) =
        RemoteItem("srv", path, path.substringAfterLast('/'), isDirectory)

    @Test
    fun `deleting a tree removes children before their parent`() = runTest {
        // A directory cannot be removed until it is empty, so the order is not a
        // preference — getting it wrong makes the delete fail.
        val session = FakeSession()
        session.dir("/movies", "a.mkv", "sub")
        session.dir("/movies/sub", "b.mkv")

        val result = operations(session).delete(listOf(item("/movies", isDirectory = true)))

        // Two files and two directories: the subdirectory and the root itself.
        assertEquals(4, result.succeeded)
        val removals = session.log.filter { it.startsWith("rm") }
        assertTrue("sub went before its contents: $removals", removals.indexOf("rm /movies/sub/b.mkv") < removals.indexOf("rmdir /movies/sub"))
        assertTrue("the root went before its contents: $removals", removals.indexOf("rmdir /movies/sub") < removals.indexOf("rmdir /movies"))
    }

    @Test
    fun `a file that cannot be deleted is reported and the rest still go`() = runTest {
        // One locked file in a folder must not turn into "nothing was deleted".
        val session = FakeSession()
        session.dir("/movies", "a.mkv", "locked.mkv", "c.mkv")
        session.failing += "/movies/locked.mkv"

        val result = operations(session).delete(listOf(item("/movies", isDirectory = true)))

        assertEquals("the files and directories that could go, did", 3, result.succeeded)
        assertEquals(1, result.failures.size)
        assertEquals("/movies/locked.mkv", result.failures.single().path)
        assertTrue(result.hasProblems)
    }

    @Test
    fun `copying a tree creates the directory before its contents`() = runTest {
        val session = FakeSession()
        session.dir("/src", "a.mkv", "sub")
        session.dir("/src/sub", "b.mkv")
        session.dir("/dst")

        val result = operations(session).copyInto(
            listOf(item("/src", isDirectory = true)),
            item("/dst", isDirectory = true),
        )

        // Two files plus the two directories created for them.
        assertEquals(4, result.succeeded)
        assertTrue(session.log.indexOf("mkdir /dst/src") < session.log.indexOf("cp /src/a.mkv /dst/src/a.mkv"))
        assertTrue(session.nodes.containsKey("/dst/src/sub/b.mkv"))
    }

    @Test
    fun `a paste onto an existing name is refused, not merged or overwritten`() = runTest {
        val session = FakeSession()
        session.file("/src/movie.mkv")
        session.dir("/dst", "movie.mkv")
        session.file("/dst/movie.mkv")

        val result = operations(session).copyInto(listOf(item("/src/movie.mkv")), item("/dst", isDirectory = true))

        assertEquals(0, result.succeeded)
        assertEquals(listOf("movie.mkv"), result.conflicts)
        assertTrue("nothing should have been written", session.log.none { it.startsWith("cp ") })
    }

    @Test
    fun `moving within one server renames rather than copying`() = runTest {
        // The whole reason a move is instant: the server just relinks the entry.
        val session = FakeSession()
        session.file("/from/movie.mkv")
        session.dir("/to")

        val result = operations(session).moveInto(listOf(item("/from/movie.mkv")), item("/to", isDirectory = true))

        assertEquals(1, result.succeeded)
        assertEquals(listOf("mv /from/movie.mkv /to/movie.mkv"), session.log)
    }

    @Test
    fun `moving to another server copies first and only then deletes`() = runTest {
        // The source is the only copy until the copy has arrived. Deleting it
        // first, or deleting it when the copy failed, loses the film.
        val session = FakeSession()
        session.file("/movies/a.mkv")
        session.dir("/dest")
        session.failing += "/movies/a.mkv"

        val target = RemoteItem("other-server", "/dest", "dest", isDirectory = true)
        val result = operations(session).moveInto(listOf(item("/movies/a.mkv")), target)

        assertTrue("the source must not be deleted when the copy failed", session.nodes.containsKey("/movies/a.mkv"))
        assertEquals(1, result.failures.size)
        assertEquals(0, result.succeeded)
    }

    @Test
    fun `renaming onto an existing name is refused`() = runTest {
        val session = FakeSession()
        session.file("/movies/a.mkv")
        session.file("/movies/b.mkv")

        val error = runCatching { operations(session).rename(item("/movies/a.mkv"), "b.mkv") }.exceptionOrNull()

        assertTrue("expected a refusal, got $error", error is DestinationExistsException)
        assertTrue("nothing should have been moved", session.log.none { it.startsWith("mv ") })
    }

    @Test
    fun `an empty selection opens no connection`() = runTest {
        var connected = false
        val ops = FileOperations {
            connected = true
            FileOperations.OpenSession(FakeSession()) {}
        }

        val result = ops.delete(emptyList())

        assertEquals(0, result.succeeded)
        assertTrue("an empty operation should not cost a round-trip", !connected)
    }
}
