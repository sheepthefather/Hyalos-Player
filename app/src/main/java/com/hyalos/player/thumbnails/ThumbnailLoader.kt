package com.hyalos.player.thumbnails

import android.graphics.Bitmap
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Hands out thumbnails: memory first, then disk, then a frame extraction.
 *
 * # Why callers may be cancelled but the work is not thrown away
 *
 * A row that scrolls off screen cancels the coroutine waiting for its thumbnail.
 * If that happens before the extraction starts, the work is simply dropped —
 * which is the point, since nobody wants it any more. Once an extraction has
 * begun it runs to completion, and the result is written to the caches under
 * [NonCancellable]: the bytes have already been pulled off the network, so
 * discarding them would only make the next scroll pay for them twice.
 *
 * # Deduplication
 *
 * Two rows can ask for the same film at once (a re-composition, or the same
 * file reachable by two paths). A per-key mutex makes the second wait and then
 * find the answer already in the cache rather than extracting it twice. Entries
 * are reference-counted so the map holds only what is actually in flight.
 */
class ThumbnailLoader(
    private val cache: ThumbnailCache,
    private val frames: FrameSource,
    private val memory: MemoryCache = MemoryCache(DEFAULT_MEMORY_BYTES),
) {
    private class InFlight {
        val mutex = Mutex()
        var waiters = 0
    }

    private val mapLock = Mutex()
    private val inFlight = mutableMapOf<ThumbnailKey, InFlight>()

    /**
     * The thumbnail for [key], or `null` if there is none to show.
     *
     * `null` is an ordinary answer, not an error: the film may be unreadable,
     * the network may be down, or thumbnails may be switched off.
     */
    suspend fun load(key: ThumbnailKey): Bitmap? {
        memory.get(key.hash)?.let { return it }
        if (cache.isDisabled) return null
        if (cache.isMissing(key)) return null

        val entry = mapLock.withLock {
            inFlight.getOrPut(key) { InFlight() }.also { it.waiters++ }
        }
        try {
            entry.mutex.withLock {
                // Re-checked inside the lock: whoever held it may have just
                // filled the cache, in which case this costs nothing.
                memory.get(key.hash)
                    ?: cache.get(key)?.also { memory.put(key.hash, it) }
                    ?: extractAndStore(key)
            }
        } finally {
            mapLock.withLock {
                if (--entry.waiters == 0) inFlight.remove(key)
            }
        }
        return memory.get(key.hash)
    }

    /** Bytes held on disk, for the settings screen. */
    suspend fun usage(): Long = cache.usage()

    /** Empty both caches. */
    suspend fun clear() {
        cache.clear()
        memory.clear()
    }

    private suspend fun extractAndStore(key: ThumbnailKey): Bitmap? =
        when (val result = frames.extract(key)) {
            is Extraction.Frame -> {
                withContext(NonCancellable) {
                    cache.put(key, result.bitmap)
                    memory.put(key.hash, result.bitmap)
                }
                result.bitmap
            }
            Extraction.Unsupported -> {
                withContext(NonCancellable) { cache.markMissing(key) }
                null
            }
            // Not written down: see Extraction.Transient.
            Extraction.Transient -> null
        }

    companion object {
        /**
         * A twelfth of the app's heap budget. Decoded thumbnails are what the
         * list actually draws, so keeping the visible screenful and then some in
         * memory is what makes scrolling back up instant.
         */
        val DEFAULT_MEMORY_BYTES: Int =
            (Runtime.getRuntime().maxMemory() / 12).coerceAtMost(16L * 1024 * 1024).toInt()
    }
}

/**
 * Decoded thumbnails, evicted least-recently-used first.
 *
 * A `LinkedHashMap` in access order rather than `android.util.LruCache`: the
 * latter is an Android class that JVM tests cannot instantiate, and this is
 * small enough to keep testable.
 */
class MemoryCache(private val maxBytes: Int) {
    private val entries = object : LinkedHashMap<String, Bitmap>(16, 0.75f, /* accessOrder = */ true) {}
    private var bytes = 0

    @Synchronized
    fun get(key: String): Bitmap? = entries[key]

    @Synchronized
    fun put(key: String, bitmap: Bitmap) {
        val size = bitmap.byteCount
        bytes += size - (entries.put(key, bitmap)?.byteCount ?: 0)
        val oldest = entries.entries.iterator()
        while (bytes > maxBytes && oldest.hasNext()) {
            bytes -= oldest.next().value.byteCount
            oldest.remove()
        }
    }

    @Synchronized
    fun clear() {
        entries.clear()
        bytes = 0
    }
}
