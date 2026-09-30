package com.hyalos.player.files

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.krystallos_ffi.EntryMetadata
import uniffi.krystallos_ffi.KernelException
import uniffi.krystallos_ffi.OpenFlags
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
                // The real size, not a placeholder: the copy's progress total is
                // summed from these, so a constant would make every progress
                // assertion meaningless.
                len = (contents[path]?.size ?: 0).toULong(),
                modifiedMs = null, createdMs = null, accessedMs = null, readOnly = false,
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
            // The bytes travel with the name. Without this a streamed copy looks
            // like it worked and then reads back nothing — which is what the
            // first run of these tests reported.
            contents.remove(from)?.let { contents[to] = it }
        }

        override suspend fun copy(from: String, to: String): ULong {
            if (from in failing) throw IOException("cannot copy $from")
            log += "cp $from $to"
            nodes[to] = false
            return 1uL
        }

        /** What each file holds, so a streamed copy can be checked byte for byte. */
        val contents = mutableMapOf<String, ByteArray>()

        /** Paths whose writes fail, for a copy that dies part way through. */
        val failingWrites = mutableSetOf<String>()

        /**
         * `createNew` is honoured rather than ignored: refusing an existing
         * destination is the behaviour the temporary-name scheme leans on, so a
         * fake that quietly allowed it would test nothing.
         */
        override suspend fun open(path: String, flags: OpenFlags): FileHandle {
            if (path in failing) throw IOException("cannot open $path")
            if (flags.createNew && path in nodes) throw KernelException.AlreadyExists(path)
            if (flags.create) {
                nodes[path] = false
                contents.putIfAbsent(path, ByteArray(0))
            }
            log += "open $path"
            return FakeHandle(path)
        }

        private inner class FakeHandle(private val path: String) : FileHandle {
            override suspend fun read(offset: ULong, len: Int): ByteArray {
                val bytes = contents[path] ?: return ByteArray(0)
                val from = offset.toInt()
                if (from >= bytes.size) return ByteArray(0)
                return bytes.copyOfRange(from, minOf(bytes.size, from + len))
            }

            /** Written as it arrives, so a failure leaves a genuinely partial file. */
            override suspend fun write(offset: ULong, data: ByteArray): Int {
                if (path in failingWrites) throw IOException("cannot write $path")
                val existing = contents[path] ?: ByteArray(0)
                val at = offset.toInt()
                if (at != existing.size) throw IOException("out of order write to $path")
                contents[path] = existing + data
                return data.size
            }

            override suspend fun release() = Unit
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

    // ---------------------------------------------------------------------
    // Copying between two sources
    // ---------------------------------------------------------------------

    /** A session per source id, so a copy can be made to cross from one to another. */
    private fun across(vararg sessions: Pair<String, FakeSession>) = FileOperations { serverId ->
        FileOperations.OpenSession(sessions.first { it.first == serverId }.second) {}
    }

    private fun at(serverId: String, path: String) =
        RemoteItem(serverId, path, path.substringAfterLast('/'), isDirectory = false)

    private fun folder(serverId: String, path: String) =
        RemoteItem(serverId, path, path.substringAfterLast('/'), isDirectory = true)

    @Test
    fun `copying to another source carries the bytes through this process`() = runTest {
        // Neither server can see the other, so nothing but this app can do it —
        // which is the whole reason the streaming path exists.
        val from = FakeSession().apply {
            file("/movies/a.mkv")
            contents["/movies/a.mkv"] = "hello".toByteArray()
        }
        val to = FakeSession().apply { dir("/backup") }

        val result = across("A" to from, "B" to to)
            .copyInto(listOf(at("A", "/movies/a.mkv")), folder("B", "/backup"))

        assertEquals(1, result.succeeded)
        assertEquals("hello", to.contents["/backup/a.mkv"]?.decodeToString())
        assertTrue("the source server was asked to copy: ${from.log}", from.log.none { it.startsWith("cp ") })
    }

    @Test
    fun `a copy within one source is still left to the server`() = runTest {
        // The fast path has to stay the fast path: a server-side copy never
        // brings the bytes here, and for a film that is the difference between
        // seconds and minutes.
        val session = FakeSession().apply {
            dir("/movies", "a.mkv")
            file("/movies/a.mkv")
        }

        session.dir("/backup")
        operations(session).copyInto(listOf(item("/movies/a.mkv")), item("/backup", isDirectory = true))

        assertTrue("expected a server-side copy: ${session.log}", session.log.any { it == "cp /movies/a.mkv /backup/a.mkv" })
        assertTrue("the bytes were carried: ${session.log}", session.log.none { it.startsWith("open ") })
    }

    @Test
    fun `a file is written under a temporary name and renamed once it is whole`() = runTest {
        // The rename is what makes an interrupted copy safe: until it happens the
        // real name does not exist, so nothing can mistake a half file for one.
        val from = FakeSession().apply {
            file("/movies/a.mkv")
            contents["/movies/a.mkv"] = "hello".toByteArray()
        }
        val to = FakeSession().apply { dir("/backup") }

        across("A" to from, "B" to to).copyInto(listOf(at("A", "/movies/a.mkv")), folder("B", "/backup"))

        val write = to.log.indexOfFirst { it == "open /backup/a.mkv.hyalos-part" }
        val rename = to.log.indexOfFirst { it == "mv /backup/a.mkv.hyalos-part /backup/a.mkv" }
        assertTrue("nothing was written under a temporary name: ${to.log}", write >= 0)
        assertTrue("it was never renamed into place: ${to.log}", rename > write)
    }

    @Test
    fun `a leftover from an earlier copy is refused, not overwritten`() = runTest {
        // The app only ever deletes and refuses files it is certain it made, and
        // a `.hyalos-part` file is one of those. Refusing rather than replacing
        // is the same rule every other write here follows.
        val from = FakeSession().apply {
            file("/movies/a.mkv")
            contents["/movies/a.mkv"] = "hello".toByteArray()
        }
        val to = FakeSession().apply {
            dir("/backup")
            file("/backup/a.mkv.hyalos-part")
            contents["/backup/a.mkv.hyalos-part"] = "half".toByteArray()
        }

        val result = across("A" to from, "B" to to)
            .copyInto(listOf(at("A", "/movies/a.mkv")), folder("B", "/backup"))

        // `OperationResult` carries a reason, not the exception, so what is
        // asserted is what the user would actually be shown: a message naming
        // the leftover. A plain "already exists" would name a file they never
        // asked to create.
        val failure = result.failures.single()
        assertTrue(
            "the leftover was not named: ${failure.reason}",
            failure.reason.contains("/backup/a.mkv.hyalos-part"),
        )
        assertEquals("the leftover was touched", "half", to.contents["/backup/a.mkv.hyalos-part"]?.decodeToString())
        assertTrue("a real file appeared", "/backup/a.mkv" !in to.nodes)
    }

    @Test
    fun `a copy that dies part way leaves nothing under the real name`() = runTest {
        val from = FakeSession().apply {
            file("/movies/a.mkv")
            contents["/movies/a.mkv"] = "hello".toByteArray()
        }
        val to = FakeSession().apply {
            dir("/backup")
            failingWrites += "/backup/a.mkv.hyalos-part"
        }

        val result = across("A" to from, "B" to to)
            .copyInto(listOf(at("A", "/movies/a.mkv")), folder("B", "/backup"))

        assertEquals(1, result.failures.size)
        assertTrue(
            "a half file was given the real name: ${to.nodes}",
            "/backup/a.mkv" !in to.nodes,
        )
    }

    @Test
    fun `a directory is carried across whole`() = runTest {
        val from = FakeSession().apply {
            dir("/movies", "sub", "top.mkv")
            file("/movies/top.mkv")
            dir("/movies/sub", "deep.mkv")
            file("/movies/sub/deep.mkv")
            contents["/movies/top.mkv"] = "one".toByteArray()
            contents["/movies/sub/deep.mkv"] = "two".toByteArray()
        }
        val to = FakeSession().apply { dir("/backup") }

        val result = across("A" to from, "B" to to)
            .copyInto(listOf(folder("A", "/movies")), folder("B", "/backup"))

        assertEquals("two files and two directories", 4, result.succeeded)
        assertEquals("one", to.contents["/backup/movies/top.mkv"]?.decodeToString())
        assertEquals("two", to.contents["/backup/movies/sub/deep.mkv"]?.decodeToString())
    }

    @Test
    fun `progress counts up to the size of what is being copied`() = runTest {
        val from = FakeSession().apply {
            file("/movies/a.mkv")
            file("/movies/b.mkv")
            contents["/movies/a.mkv"] = "hello".toByteArray()
            contents["/movies/b.mkv"] = "world!".toByteArray()
        }
        val to = FakeSession().apply { dir("/backup") }

        val seen = mutableListOf<Pair<ULong, ULong>>()
        across("A" to from, "B" to to).copyInto(
            targets = listOf(at("A", "/movies/a.mkv"), at("A", "/movies/b.mkv")),
            targetDirectory = folder("B", "/backup"),
            onProgress = { copied, total -> seen += copied to total },
        )

        assertTrue("progress was never reported", seen.isNotEmpty())
        assertEquals("the total was wrong", 11uL, seen.last().second)
        assertEquals("it did not finish at the total", 11uL, seen.last().first)
        assertEquals("it did not count up", seen.map { it.first }.sorted(), seen.map { it.first })
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
