package com.hyalos.player.playback

import com.hyalos.player.kernel.READ_ONLY
import com.hyalos.player.kernel.shutdown
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uniffi.krystallos_ffi.RemoteFile
import uniffi.krystallos_ffi.Session

/**
 * A [ReaderSource] bound to one server, with at most one file open on it.
 *
 * Named for what it is rather than for its first user: it began under the
 * thumbnails and is now also how the file-info probe opens a header. Its callers
 * are the ones that know whether they are reading a frame or a `moov` box.
 *
 * Deliberately *not* [PlaybackConnection], though the two look alike. That one
 * keeps every file it opens, because the player re-opens the same file
 * constantly across seeks; a one-shot read has the opposite shape — one file,
 * read once, finished. Reusing it here would leave a server-side handle open for
 * every film the user ever scrolled past.
 *
 * The session itself *is* kept: reconnecting per read would mean an SMB
 * handshake per row.
 */
class ServerReaderSource(
    private val connect: suspend () -> Session,
    private val scope: CoroutineScope,
) : ReaderSource {
    private val lock = Mutex()
    private var session: Session? = null
    private var openPath: String? = null
    private var openFile: RemoteFile? = null
    private var openReader: RandomReader? = null

    override suspend fun reader(path: String): RandomReader = lock.withLock {
        // The same object for the same path: ChunkedReader compares readers by
        // identity, so handing it a fresh one would discard a window it holds.
        openReader?.takeIf { openPath == path }?.let { return@withLock it }

        closeOpenFile()
        val session = session ?: connect().also { session = it }
        val file = session.open(path, READ_ONLY)
        val reader = FileReader(file)
        openPath = path
        openFile = file
        openReader = reader
        reader
    }

    /**
     * The connection is dead; drop it so the next read reconnects.
     *
     * Released in the background rather than awaited: a disconnect queues behind
     * whatever the session is doing, and the caller here has nothing to wait for.
     */
    override suspend fun invalidate() {
        val dead = lock.withLock {
            val s = session
            session = null
            val f = takeOpenFile()
            s to f
        }
        scope.launch {
            dead.second?.let { runCatching { it.shutdown() } }
            dead.first?.let { runCatching { it.shutdown() } }
        }
    }

    /**
     * Release the file used by the last read, keeping the session.
     *
     * Called by the thumbnail loader after every film, when the session thread is
     * idle, so the disconnect round-trip behind it is short. The next one is
     * almost always a different film, so holding this one open buys nothing and
     * costs a handle on the server.
     */
    suspend fun releaseFile() {
        val file = lock.withLock { takeOpenFile() }
        file?.let { runCatching { it.shutdown() } }
    }

    suspend fun close() {
        val dead = lock.withLock {
            val s = session
            session = null
            s to takeOpenFile()
        }
        dead.second?.let { runCatching { it.shutdown() } }
        dead.first?.let { runCatching { it.shutdown() } }
    }

    /** Must be called under [lock]. */
    private fun takeOpenFile(): RemoteFile? {
        val file = openFile ?: return null
        openPath = null
        openFile = null
        openReader = null
        return file
    }

    private suspend fun closeOpenFile() {
        takeOpenFile()?.let { runCatching { it.shutdown() } }
    }

    private class FileReader(private val file: RemoteFile) : RandomReader {
        override val length: Long = file.len().toLong()

        override suspend fun readAt(offset: Long, len: Int): ByteArray =
            file.readAt(offset.toULong(), len.toUInt()).data
    }
}
