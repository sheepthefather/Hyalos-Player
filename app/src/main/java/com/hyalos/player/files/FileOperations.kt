package com.hyalos.player.files

import com.hyalos.player.kernel.RemotePath
import com.hyalos.player.kernel.shutdown
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
    suspend fun copyInto(targets: List<RemoteItem>, targetDirectory: RemoteItem): OperationResult =
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
    suspend fun moveInto(targets: List<RemoteItem>, targetDirectory: RemoteItem): OperationResult {
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
        val copied = copyInto(targets, targetDirectory)
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
    }
}

/** Something with that name is already there. */
class DestinationExistsException(val path: String) : Exception("already exists: $path")
