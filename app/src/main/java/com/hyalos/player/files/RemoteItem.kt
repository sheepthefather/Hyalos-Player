package com.hyalos.player.files

import uniffi.krystallos_ffi.EntryMetadata
import uniffi.krystallos_ffi.Kind
import uniffi.krystallos_ffi.Session

/**
 * One file or folder on a server.
 *
 * Carries the server as well as the path, because a clipboard outlives the
 * screen it was filled from and the user may well paste onto a different server
 * — which the file operations have to notice, since moving between servers is a
 * copy rather than a rename.
 */
data class RemoteItem(
    val serverId: String,
    val path: String,
    val name: String,
    val isDirectory: Boolean,
)

enum class ClipboardMode { COPY, CUT }

/** What was copied or cut, and which of the two it was. */
data class ClipboardContent(val mode: ClipboardMode, val items: List<RemoteItem>)

/**
 * The subset of a session the file operations use.
 *
 * An interface for the same reason [`ReaderSource`] is one: a real `Session` is
 * a generated UniFFI class that only exists next to a live server, and the
 * traversal and result-aggregating logic here is exactly the part worth testing
 * without one.
 *
 * [`ReaderSource`]: com.hyalos.player.playback.ReaderSource
 */
interface FileSession {
    suspend fun list(path: String): List<DirectoryEntry>
    suspend fun stat(path: String): EntryMetadata
    suspend fun mkdir(path: String)
    suspend fun removeFile(path: String)
    suspend fun removeDir(path: String)
    suspend fun rename(from: String, to: String)

    /** Bytes copied. Server-side where the protocol allows it. */
    suspend fun copy(from: String, to: String): ULong
}

/** A directory entry as the file operations need it: a name and whether it is a folder. */
data class DirectoryEntry(val name: String, val isDirectory: Boolean)

/** A real session, narrowed to what [FileSession] promises. */
class KernelFileSession(private val session: Session) : FileSession {
    override suspend fun list(path: String): List<DirectoryEntry> =
        session.list(path).map {
            DirectoryEntry(it.name, it.metadata.kind == Kind.DIRECTORY)
        }

    override suspend fun stat(path: String) = session.stat(path)

    override suspend fun mkdir(path: String) = session.mkdir(path)

    override suspend fun removeFile(path: String) = session.removeFile(path)

    override suspend fun removeDir(path: String) = session.removeDir(path)

    override suspend fun rename(from: String, to: String) = session.rename(from, to)

    override suspend fun copy(from: String, to: String): ULong = session.copy(from, to)
}
