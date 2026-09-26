package com.hyalos.player.thumbnails

import com.hyalos.player.playback.ServerReaderSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import uniffi.krystallos_ffi.Session

/**
 * One [ServerReaderSource] per server, built on demand.
 *
 * Needed because `ReaderSource.reader` takes only a path: a source is bound to
 * the server it was connected to, while the user moves between servers freely.
 */
class ThumbnailSources(
    private val connect: suspend (serverId: String) -> Session,
    private val scope: CoroutineScope,
) {
    private val sources = java.util.concurrent.ConcurrentHashMap<String, ServerReaderSource>()

    fun forServer(serverId: String): ServerReaderSource =
        sources.getOrPut(serverId) { ServerReaderSource({ connect(serverId) }, scope) }

    /** Forget [serverId]'s connection — its settings changed, or it was deleted. */
    fun invalidate(serverId: String) {
        sources.remove(serverId)?.let { source -> scope.launch { source.close() } }
    }

    fun closeAll() {
        sources.values.forEach { source -> scope.launch { source.close() } }
    }
}
