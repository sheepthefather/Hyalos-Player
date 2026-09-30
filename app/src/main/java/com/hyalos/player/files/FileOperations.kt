package com.hyalos.player.files

import com.hyalos.player.kernel.RemotePath
import com.hyalos.player.kernel.shutdown
import uniffi.krystallos_ffi.OpenFlags
import uniffi.krystallos_ffi.KernelException

/**
 * Renaming, deleting, copying and moving — the operations a file browser needs
 * and the kernel deliberately does not provide wholesale.
 *
 * # Recursion lives here
 *
 * The kernel copies and deletes **one item** at a time; refusing a non-empty
 * directory is a deliberate choice there, so that the caller keeps control of
 * what a half-finished operation means. That control is this class: it walks
 * the tree, carries on past items that fail, and returns a result that says how
 * much got done and what did not.
 *
 * # One session per operation, and it is not the browsing one
 *
 * A kernel session runs one operation at a time with a 20-second timeout, and
 * browsing shares one per server. Copying a film through the shared session
 * would freeze the directory listing behind it, so every operation here takes
 * its own connection and closes it afterwards.
 *
 * # Nothing here overwrites
 *
 * Renames and pastes check the destination first and record a conflict instead.
 * The kernel refuses to overwrite on copy for the same reason: replacing a film
 * is a decision the user has to make, not something a mistyped name should do.
 */
class FileOperations(private val connect: suspend (serverId: String) -> OpenSession) {

    companion object {
        /** Ceiling on the pre-delete count, so a vast tree cannot stall the dialog. */
        const val COUNT_LIMIT = 500
    }

    /**
     * A session opened for one operation, together with the way to release it.
     *
     * The two travel together because the caller must not be able to forget the
     * second: an unclosed connection stays open on the server until the app
     * exits or the server times it out.
     */
    class OpenSession(val session: FileSession, private val release: suspend () -> Unit) {
        suspend fun close() = release()
    }

    /**
     * How many items deleting [targets] would remove, counting what is inside
     * directories.
     *
     * Worth a round-trip before a delete: "delete 47 items" is a different
     * proposition from "delete Series 3", and someone who selected a folder to
     * be rid of it deserves to know it holds a whole season.
     *
     * Stops at [limit] so a vast tree cannot stall the confirmation. A count at
     * the limit is reported as at-least-that-many by the caller.
     */
    suspend fun countContents(targets: List<RemoteItem>, limit: Int = COUNT_LIMIT): Int =
        withSession(targets) { session, _ ->
            // The selected items themselves always count, however the walk goes.
            var count = targets.size
            val pending = ArrayDeque(targets.filter { it.isDirectory }.map { it.path })
            while (pending.isNotEmpty() && count < limit) {
                val directory = pending.removeFirst()
                val entries = try {
                    session.list(directory)
                } catch (_: Exception) {
                    // Unreadable now, or unreadable when it comes to deleting it
                    // — either way the count cannot include what cannot be seen.
                    continue
                }
                for (entry in entries) {
                    count++
                    if (entry.isDirectory) pending += RemotePath.join(directory, entry.name)
                    if (count >= limit) break
                }
            }
            count
        }

    /** Rename one item within its own directory. */
    suspend fun rename(item: RemoteItem, newName: String) {
        val parent = RemotePath.parent(item.path) ?: RemotePath.ROOT
        val target = RemotePath.join(parent, newName)
        withSession(item.serverId) { session ->
            if (exists(session, target)) {
                throw DestinationExistsException(target)
            }
            session.rename(item.path, target)
        }
    }

    /** Delete [targets], recursing into directories. Parents go after their children. */
    suspend fun delete(targets: List<RemoteItem>): OperationResult = withSession(targets) { session, result ->
        for (target in targets) {
            deleteTree(session, target, result)
        }
        result
    }

    /**
     * Copy [targets] into [targetDirectory].
     *
     * Used for both paste-a-copy and the cross-server half of a move; the caller
     * deletes the sources afterwards in the second case.
     */
    suspend fun copyInto(
        targets: List<RemoteItem>,
        targetDirectory: RemoteItem,
        onProgress: ((copied: ULong, total: ULong) -> Unit)? = null,
    ): OperationResult {
        // Both ends on one machine means the *server* can do this, instantly and
        // without the bytes coming here at all. Anything else has to be carried,
        // because neither end can see the other — see `copyAcross`.
        if (targets.any { it.serverId != targetDirectory.serverId }) {
            return copyAcross(targets, targetDirectory, onProgress)
        }
        return copyWithin(targets, targetDirectory)
    }

    private suspend fun copyWithin(
        targets: List<RemoteItem>,
        targetDirectory: RemoteItem,
    ): OperationResult =
        withSession(targets) { session, result ->
            for (target in targets) {
                val destination = RemotePath.join(targetDirectory.path, target.name)
                if (exists(session, destination)) {
                    result.recordConflict(target.name)
                    continue
                }
                copyTree(session, target.path, destination, target.isDirectory, result)
            }
            result
        }

    /**
     * Move [targets] into [targetDirectory].
     *
     * Within one server this is a rename, which the server does instantly and
     * without touching the bytes. Across servers it is a copy followed by a
     * delete — and the delete only runs for the items whose copy succeeded, so
     * a failure never destroys the only copy.
     */
    suspend fun moveInto(
        targets: List<RemoteItem>,
        targetDirectory: RemoteItem,
        onProgress: ((copied: ULong, total: ULong) -> Unit)? = null,
    ): OperationResult {
        val sameServer = targets.all { it.serverId == targetDirectory.serverId }

        if (sameServer) {
            return withSession(targets) { session, result ->
                for (target in targets) {
                    val destination = RemotePath.join(targetDirectory.path, target.name)
                    if (exists(session, destination)) {
                        result.recordConflict(target.name)
                        continue
                    }
                    try {
                        session.rename(target.path, destination)
                        result.succeeded++
                    } catch (e: KernelException) {
                        result.recordFailure(target.name, e)
                    }
                }
                result
            }
        }

        // Different servers: copy first, and only delete what actually arrived.
        val copied = copyInto(targets, targetDirectory, onProgress)
        val arrived = targets.filter { target ->
            if (copied.conflicts.contains(target.name)) return@filter false
            // A failure *anywhere* in the tree means the copy is incomplete, so
            // the source must stay: deleting a partially-copied directory loses
            // whatever did not make it. Comparing the recorded path against the
            // source path and everything beneath it is what makes that hold —
            // comparing names would match nothing, since failures carry paths.
            copied.failures.none { failure ->
                failure.path == target.path || failure.path.startsWith("${target.path}/")
            }
        }
        val deleted = delete(arrived)
        return OperationResult(
            succeeded = deleted.succeeded,
            // Copies rather than aliases: the two results are read afterwards to
            // decide what to report, and sharing a list would let one edit the other.
            failures = (copied.failures + deleted.failures).toMutableList(),
            conflicts = copied.conflicts.toMutableList(),
        )
    }

    private suspend fun deleteTree(session: FileSession, root: RemoteItem, result: OperationResult) {
        if (!root.isDirectory) {
            runCatching { session.removeFile(root.path) }
                .onSuccess { result.succeeded++ }
                .onFailure { result.recordFailure(root.path, it) }
            return
        }

        // Collected first, then removed deepest-first: a directory cannot go
        // until it is empty. An explicit queue rather than recursion, so a
        // pathologically deep tree cannot exhaust the stack.
        val directories = ArrayDeque<String>()
        val files = mutableListOf<String>()
        val pending = ArrayDeque<String>()
        pending += root.path

        while (pending.isNotEmpty()) {
            val directory = pending.removeFirst()
            directories += directory
            val entries = try {
                session.list(directory)
            } catch (e: Exception) {
                // Its children cannot be enumerated, so it cannot be emptied
                // either — record the failure and let the rmdir below report it
                // too, rather than pretending the subtree was fine.
                result.recordFailure(directory, e)
                continue
            }
            for (entry in entries) {
                val child = RemotePath.join(directory, entry.name)
                if (entry.isDirectory) pending += child else files += child
            }
        }

        for (file in files) {
            runCatching { session.removeFile(file) }
                .onSuccess { result.succeeded++ }
                .onFailure { result.recordFailure(file, it) }
        }
        for (directory in directories.reversed()) {
            runCatching { session.removeDir(directory) }
                .onSuccess { result.succeeded++ }
                .onFailure { result.recordFailure(directory, it) }
        }
    }

    private suspend fun copyTree(
        session: FileSession,
        source: String,
        destination: String,
        sourceIsDirectory: Boolean,
        result: OperationResult,
    ) {
        data class Work(val from: String, val to: String, val isDirectory: Boolean)

        val pending = ArrayDeque<Work>()
        pending += Work(source, destination, sourceIsDirectory)

        while (pending.isNotEmpty()) {
            val work = pending.removeFirst()
            if (!work.isDirectory) {
                runCatching { session.copy(work.from, work.to) }
                    .onSuccess { result.succeeded++ }
                    .onFailure { result.recordFailure(work.from, it) }
                continue
            }

            val entries = try {
                session.list(work.from)
            } catch (e: Exception) {
                result.recordFailure(work.from, e)
                continue
            }
            // The directory itself is created before its contents, so a failure
            // part-way leaves a directory holding what did copy rather than
            // nothing at all — the children are reported individually either way.
            try {
                session.mkdir(work.to)
                result.succeeded++
            } catch (e: Exception) {
                result.recordFailure(work.to, e)
                continue
            }
            for (entry in entries) {
                pending += Work(
                    from = RemotePath.join(work.from, entry.name),
                    to = RemotePath.join(work.to, entry.name),
                    isDirectory = entry.isDirectory,
                )
            }
        }
    }

    /**
     * Copy between two sources, carrying the bytes through this process.
     *
     * A server can copy within itself and nothing else: when the clipboard holds
     * something from one machine and the paste lands on another, no single
     * session can see both ends. This app is the only thing that can, so the
     * bytes come here, at the cost of the transfer being twice as long as the
     * network path between the two.
     *
     * **Every file is written under a temporary name and renamed once it has
     * arrived.** A copy that dies half way therefore never leaves a file wearing
     * the real name — which is the failure that matters, because a half file
     * with a right-looking name is worse than an obviously broken one: it plays
     * for a while and then stops, and the next paste collides with it. The
     * leftover is named `<name>.hyalos-part`, stays visible, and is refused
     * rather than overwritten if the same file is copied again.
     */
    private suspend fun copyAcross(
        targets: List<RemoteItem>,
        targetDirectory: RemoteItem,
        onProgress: ((ULong, ULong) -> Unit)?,
    ): OperationResult {
        val result = OperationResult()
        val total = if (onProgress == null) 0uL else sizeOf(targets, targets.first().serverId)
        var copied = 0uL

        withSession(targets.first().serverId) { source ->
            withSession(targetDirectory.serverId) { destination ->
                for (target in targets) {
                    val to = RemotePath.join(targetDirectory.path, target.name)
                    if (exists(destination, to)) {
                        result.recordConflict(target.name)
                        continue
                    }
                    copyAcrossTree(source, destination, target.path, to, target.isDirectory, result) {
                        copied += it
                        onProgress?.invoke(copied, total)
                    }
                }
            }
        }
        return result
    }

    private suspend fun copyAcrossTree(
        source: FileSession,
        destination: FileSession,
        from: String,
        to: String,
        sourceIsDirectory: Boolean,
        result: OperationResult,
        onBytes: (ULong) -> Unit,
    ) {
        data class Work(val from: String, val to: String, val isDirectory: Boolean)

        val pending = ArrayDeque<Work>()
        pending += Work(from, to, sourceIsDirectory)

        while (pending.isNotEmpty()) {
            val work = pending.removeFirst()
            if (!work.isDirectory) {
                runCatching { streamFile(source, destination, work.from, work.to, onBytes) }
                    .onSuccess { result.succeeded++ }
                    .onFailure { result.recordFailure(work.from, it) }
                continue
            }

            val entries = try {
                source.list(work.from)
            } catch (e: Exception) {
                result.recordFailure(work.from, e)
                continue
            }
            try {
                destination.mkdir(work.to)
                result.succeeded++
            } catch (e: Exception) {
                result.recordFailure(work.to, e)
                continue
            }
            for (entry in entries) {
                pending += Work(
                    from = RemotePath.join(work.from, entry.name),
                    to = RemotePath.join(work.to, entry.name),
                    isDirectory = entry.isDirectory,
                )
            }
        }
    }

    /** One file, streamed. Returns the bytes written. */
    private suspend fun streamFile(
        source: FileSession,
        destination: FileSession,
        from: String,
        to: String,
        onBytes: (ULong) -> Unit,
    ): ULong {
        val temporary = "$to$PARTIAL_SUFFIX"
        val input = source.open(from, READ_ONLY)
        try {
            val output = try {
                // `create_new` rather than a check-then-write, so that a leftover
                // from an earlier failure is refused by the same call that would
                // otherwise race with a second paste of the same file. The server
                // arbitrates; we only translate the refusal.
                destination.open(temporary, WRITE_NEW)
            } catch (e: KernelException.AlreadyExists) {
                throw PartialFileInTheWayException(temporary)
            }
            var written = 0uL
            try {
                while (true) {
                    val chunk = input.read(written, COPY_CHUNK)
                    if (chunk.isEmpty()) break
                    var offered = 0
                    while (offered < chunk.size) {
                        val n = output.write(written + offered.toULong(), chunk.copyOfRange(offered, chunk.size))
                        if (n <= 0) throw java.io.IOException("short write at $written on $temporary")
                        offered += n
                    }
                    written += chunk.size.toULong()
                    onBytes(chunk.size.toULong())
                }
            } finally {
                output.release()
            }
            // Only now does it earn the real name.
            destination.rename(temporary, to)
            return written
        } finally {
            input.release()
        }
    }

    /**
     * What the whole copy will move, for a progress bar.
     *
     * Walked before anything is written, because a total that appears half way
     * through is not a total. Metadata only — no bytes — but it is a full pass
     * over the tree, which is the price of showing a proportion rather than a
     * count that means nothing on its own.
     */
    private suspend fun sizeOf(targets: List<RemoteItem>, serverId: String): ULong {
        data class Work(val path: String, val isDirectory: Boolean)

        var total = 0uL
        val pending = ArrayDeque<Work>()
        targets.forEach { pending += Work(it.path, it.isDirectory) }

        withSession(serverId) { session ->
            while (pending.isNotEmpty()) {
                val work = pending.removeFirst()
                if (!work.isDirectory) {
                    // A stat that fails is not worth failing the copy over: the
                    // total is a courtesy, and the copy itself will report
                    // whatever is really wrong.
                    runCatching { total += session.stat(work.path).len.toULong() }
                    continue
                }
                val entries = runCatching { session.list(work.path) }.getOrNull() ?: continue
                for (entry in entries) {
                    pending += Work(RemotePath.join(work.path, entry.name), entry.isDirectory)
                }
            }
        }
        return total
    }

    private suspend fun exists(session: FileSession, path: String): Boolean =
        try {
            session.stat(path)
            true
        } catch (_: KernelException.NotFound) {
            false
        }

    /** Run [block] on a connection of its own, closed whatever happens. */
    private suspend fun <T> withSession(
        targets: List<RemoteItem>,
        block: suspend (FileSession, OperationResult) -> T,
    ): T {
        val serverId = targets.firstOrNull()?.serverId
        // Nothing to do: opening a connection to discover that would be a
        // round-trip for nothing.
            ?: return block(UnreachableSession, OperationResult())
        return withSession(serverId) { session -> block(session, OperationResult()) }
    }

    private suspend fun <T> withSession(serverId: String, block: suspend (FileSession) -> T): T {
        val closer = connect(serverId)
        try {
            return block(closer.session)
        } finally {
            runCatching { closer.close() }
        }
    }

    /**
     * A session that does nothing, for the empty-target case.
     *
     * Every operation over an empty list is a no-op, and opening a connection to
     * find that out would be a round-trip for nothing.
     */
    private object UnreachableSession : FileSession {
        override suspend fun list(path: String) = emptyList<DirectoryEntry>()
        override suspend fun stat(path: String) = throw KernelException.NotFound(path)
        override suspend fun mkdir(path: String) = Unit
        override suspend fun removeFile(path: String) = Unit
        override suspend fun removeDir(path: String) = Unit
        override suspend fun rename(from: String, to: String) = Unit
        override suspend fun copy(from: String, to: String): ULong = 0uL
        override suspend fun open(path: String, flags: OpenFlags): FileHandle =
            throw KernelException.NotFound(path)
    }
}

/** Something with that name is already there. */
class DestinationExistsException(val path: String) : Exception("already exists: $path")

/**
 * A copy that died earlier left its half-written file where this one needs to
 * write.
 *
 * Its own type rather than a plain failure because the message has to say
 * something a generic "already exists" cannot: the file it names is not the one
 * the user asked to create, and deleting it is a decision only they can make.
 */
class PartialFileInTheWayException(val path: String) :
    Exception("an unfinished copy is in the way: $path")

/**
 * What a half-written file is called until it is whole.
 *
 * Suffixed rather than prefixed so it sorts beside the file it will become, and
 * carrying the app's name so that it cannot be mistaken for the user's own file
 * — the app deletes and refuses things under this name, and it may only do that
 * to files it is certain it made.
 *
 * Not a video extension, so a leftover cannot be tapped and played as though it
 * were finished.
 */
private const val PARTIAL_SUFFIX = ".hyalos-part"

/**
 * Open an existing file for reading.
 *
 * Written out rather than taken from `OpenFlags.read_only()`: uniffi generates
 * the record's constructor, not the helpers written beside it in Rust.
 */
private val READ_ONLY = OpenFlags(
    read = true,
    write = false,
    create = false,
    createNew = false,
    truncate = false,
)

/** Write-only and new: the server refuses rather than replaces. */
private val WRITE_NEW = OpenFlags(
    read = false,
    write = true,
    create = true,
    createNew = true,
    truncate = false,
)

/** 1 MiB. One SMB read and one write per chunk, small enough to report progress often. */
private const val COPY_CHUNK = 1 shl 20
