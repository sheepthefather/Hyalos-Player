package com.hyalos.player.thumbnails

import com.hyalos.player.kernel.READ_ONLY
import com.hyalos.player.kernel.shutdown
import com.hyalos.player.playback.RandomReader
import com.hyalos.player.playback.ReaderSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uniffi.krystallos_ffi.RemoteFile
import uniffi.krystallos_ffi.Session

/**
 * The session thumbnails are extracted over, with at most one file open on it.
 *
 * Deliberately *not* [com.hyalos.player.playback.PlaybackConnection], though the
 * two look alike. That one keeps every file it opens, because the player
 * re-opens the same file constantly across seeks; extraction has the opposite
 * shape — one file, read once, finished. Reusing it here would leave a
 * server-side handle open for every film the user ever scrolled past.
 *
 * The session itself *is* kept: reconnecting per thumbnail would mean an SMB
 * handshake per row.
 */
class ThumbnailSource(
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
     * The connection is dead; drop it so the next extraction reconnects.
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
     * Release the file used by the last extraction, keeping the session.
     *
     * Called after every extraction, when the session thread is idle, so the
     * disconnect round-trip behind it is short. The next extraction is almost
     * always a different film, so holding this one open buys nothing and costs a
     * handle on the server.
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

/**
 * One [ThumbnailSource] per server, built on demand.
 *
 * Needed because [ReaderSource.reader] takes only a path: a source is bound to
 * the server it was connected to, while the user moves between servers freely.
 */
class ThumbnailSources(
    private val connect: suspend (serverId: String) -> Session,
    private val scope: CoroutineScope,
) {
    private val sources = java.util.concurrent.ConcurrentHashMap<String, ThumbnailSource>()

    fun forServer(serverId: String): ThumbnailSource =
        sources.getOrPut(serverId) { ThumbnailSource({ connect(serverId) }, scope) }

    /** Forget [serverId]'s connection — its settings changed, or it was deleted. */
    fun invalidate(serverId: String) {
        sources.remove(serverId)?.let { source -> scope.launch { source.close() } }
    }

    fun closeAll() {
        sources.values.forEach { source -> scope.launch { source.close() } }
        sources.clear()
    }
}
