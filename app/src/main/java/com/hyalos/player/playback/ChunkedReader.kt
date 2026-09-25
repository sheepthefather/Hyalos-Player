package com.hyalos.player.playback

/** Positioned reads from one open file. */
interface RandomReader {
    /** The size when the file was opened. */
    val length: Long

    /**
     * Up to [len] bytes starting at [offset]. Shorter only at end of file, and
     * empty at or past it — the kernel's facade loops over short backend
     * reads, so a short result really does mean the file ended.
     */
    suspend fun readAt(offset: Long, len: Int): ByteArray
}

/**
 * A read buffer between ExoPlayer and the kernel.
 *
 * # Why this exists
 *
 * ExoPlayer's extractors read in whatever sizes suit them — a few bytes of a
 * box header, sometimes a single byte, hundreds of thousands of times while
 * probing a file. Each read that reached the kernel would cross the FFI and
 * then the network. So reads are served from a window of [windowSize] bytes,
 * and only a miss goes to the kernel.
 *
 * The Rust kernel has a read-ahead layer of its own, but the FFI does not use
 * it, and it could not help here anyway: every ExoPlayer read would still
 * cross the FFI to reach it. This buffer has to be on the Kotlin side.
 *
 * # Hit and miss are separate calls
 *
 * [tryRead] is plain, non-suspending code, and [fill] is the only part that
 * suspends. The caller bridges into coroutines — `runBlocking` on ExoPlayer's
 * loader thread — only on a miss, rather than paying for an event loop on every
 * one-byte read.
 *
 * Not thread-safe: one instance belongs to one DataSource, which ExoPlayer
 * drives from one loader thread at a time.
 */
class ChunkedReader(private val windowSize: Int = DEFAULT_WINDOW) {
    private var source: RandomReader? = null
    private var windowStart = 0L
    private var window = EMPTY

    /**
     * Copy up to [length] bytes at [position] from the window into [dst].
     *
     * @return the number of bytes copied, or [MISS] if the window does not hold
     *   [position] — call [fill] and try again.
     */
    fun tryRead(source: RandomReader, position: Long, dst: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        // Identity, not equality: a different reader is a different file, even
        // at the same offsets.
        if (source !== this.source) return MISS
        if (position < windowStart || position >= windowStart + window.size) return MISS
        val from = (position - windowStart).toInt()
        val n = minOf(length, window.size - from)
        System.arraycopy(window, from, dst, offset, n)
        return n
    }

    /**
     * Load the window starting at [position].
     *
     * Starts at the position rather than aligned to a boundary: ExoPlayer reads
     * forward from wherever it opened, so the bytes after [position] are the
     * ones about to be wanted.
     *
     * @return `false` if the file has nothing at [position] — end of input.
     */
    suspend fun fill(source: RandomReader, position: Long): Boolean {
        val data = source.readAt(position, windowSize)
        // The returned array becomes the window as-is: no second copy.
        this.source = source
        windowStart = position
        window = data
        return data.isNotEmpty()
    }

    companion object {
        /**
         * One mebibyte: at a 100 Mbps 4K stream (~12 MiB/s) that is about twelve
         * kernel round-trips a second, each of which costs milliseconds on a
         * LAN. Larger would cost memory for no visible gain; ExoPlayer keeps its
         * own tens of seconds of buffer above this.
         */
        const val DEFAULT_WINDOW = 1024 * 1024

        /** Returned by [tryRead] when the window does not cover the position. */
        const val MISS = -2

        private val EMPTY = ByteArray(0)
    }
}
