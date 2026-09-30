package com.hyalos.player.files

import uniffi.krystallos_ffi.EntryMetadata
import uniffi.krystallos_ffi.Kind
import uniffi.krystallos_ffi.OpenFlags
import uniffi.krystallos_ffi.RemoteFile
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

    /**
     * Open a file so its bytes can pass through this process.
     *
     * The other operations here are answered by the server. This one is what
     * makes a copy between *two* sources possible at all: neither server can
     * see the other, so somebody has to carry the bytes, and the only thing
     * that can see both is this app. `copy` stays the fast path for the case
     * where both ends are the same machine.
     */
    suspend fun open(path: String, flags: OpenFlags): FileHandle
}

/**
 * A file open across a copy.
 *
 * Deliberately narrow: read, write, release. Everything else a file can do —
 * seeking, truncating, appending — is machinery a straight copy does not need,
 * and each addition is another thing a backend has to get right.
 */
interface FileHandle {
    /** Up to [len] bytes at [offset]. An empty array means end of file. */
    suspend fun read(offset: ULong, len: Int): ByteArray

    /** Bytes actually written, which may be fewer than were offered. */
    suspend fun write(offset: ULong, data: ByteArray): Int

    /** Close. Harmless to call twice. */
    suspend fun release()
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

    override suspend fun open(path: String, flags: OpenFlags): FileHandle =
        KernelFileHandle(session.open(path, flags))
}

/** A [RemoteFile] on the kernel's terms, narrowed to what [FileHandle] promises. */
class KernelFileHandle(private val file: RemoteFile) : FileHandle {
    override suspend fun read(offset: ULong, len: Int): ByteArray =
        file.readAt(offset, len.toUInt()).data

    override suspend fun write(offset: ULong, data: ByteArray): Int =
        file.writeAt(offset, data).toInt()

    override suspend fun release() {
        // A failed close is not worth propagating: the handle is going away
        // either way, and the copy it belonged to has already succeeded or
        // already failed on its own terms.
        runCatching { file.release() }
    }
}
