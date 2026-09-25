package com.hyalos.player.playback

import com.hyalos.player.kernel.READ_ONLY
import com.hyalos.player.kernel.shutdown
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uniffi.krystallos_ffi.RemoteFile
import uniffi.krystallos_ffi.Session
import java.io.IOException

/** Where a DataSource gets its readers. An interface so tests can supply fakes. */
interface ReaderSource {
    /** The reader for [path], opening it if needed. The same object for the same path. */
    suspend fun reader(path: String): RandomReader

    /** The connection is dead; drop it so the next [reader] reconnects. */
    suspend fun invalidate()
}

/**
 * One player's connection: a dedicated session and the files open on it.
 *
 * # Why it outlives the DataSource's open/close
 *
 * ExoPlayer closes its DataSource and opens it again at the new position on
 * every seek. If each open connected and authenticated anew, every scrub of
 * the seek bar would pay an SMB handshake. So the session and the open file
 * live here, across opens, and are released only by [close] when the player
 * goes away.
 *
 * # Locking
 *
 * The mutex guards acquiring the session and files, never the reads. A read
 * stuck on a bad network can take up to the kernel's 20-second timeout, and
 * [close] must not wait behind it.
 */
class PlaybackConnection(
    private val connect: suspend () -> Session,
    private val scope: CoroutineScope,
) : ReaderSource {
    private val lock = Mutex()
    private var session: Session? = null
    private val files = HashMap<String, Pair<RemoteFile, RandomReader>>()
    private var closed = false

    override suspend fun reader(path: String): RandomReader = lock.withLock {
        if (closed) throw IOException("playback connection is closed")
        files[path]?.let { return it.second }
        val session = session ?: connect().also { session = it }
        val file = session.open(path, READ_ONLY)
        val reader = FileReader(file)
        files[path] = file to reader
        reader
    }

    override suspend fun invalidate() {
        val (dead, deadFiles) = lock.withLock { detach() }
        // Not awaited: the session is already broken, and the loader thread
        // that noticed should get on with reporting it.
        scope.launch { release(dead, deadFiles) }
    }

    /** Release everything. Idempotent. The session is not reused afterwards. */
    suspend fun close() {
        val (session, files) = lock.withLock {
            closed = true
            detach()
        }
        release(session, files)
    }

    private fun detach(): Pair<Session?, List<RemoteFile>> {
        val result = session to files.values.map { it.first }
        session = null
        files.clear()
        return result
    }

    private suspend fun release(session: Session?, files: List<RemoteFile>) {
        files.forEach { it.shutdown() }
        session?.shutdown()
    }

    /** A kernel file as a [RandomReader]. */
    private class FileReader(private val file: RemoteFile) : RandomReader {
        override val length: Long = file.len().toLong()

        override suspend fun readAt(offset: Long, len: Int): ByteArray =
            file.readAt(offset.toULong(), len.toUInt()).data
    }
}
