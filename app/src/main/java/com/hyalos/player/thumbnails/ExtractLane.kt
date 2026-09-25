package com.hyalos.player.thumbnails

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.inspector.frame.FrameExtractor
import com.hyalos.player.playback.KrystallosDataSource
import com.hyalos.player.playback.KrystallosUri
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import uniffi.krystallos_ffi.KernelException
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** What one attempt at a frame produced. */
sealed interface Extraction {
    data class Frame(val bitmap: Bitmap) : Extraction

    /**
     * There is no frame to be had — the format or its codec is beyond us. Worth
     * remembering, so a scroll does not retry it forever.
     */
    data object Unsupported : Extraction

    /**
     * The attempt failed for a reason that may pass — the network, a session
     * that died, a timeout. **Deliberately not remembered**: caching one dropped
     * connection would poison a whole directory of thumbnails until the app is
     * reinstalled.
     */
    data object Transient : Extraction
}

/** Somewhere frames come from. An interface so the scheduler is testable without a video. */
fun interface FrameSource {
    suspend fun extract(key: ThumbnailKey): Extraction
}

/**
 * One worker thread that pulls frames out of videos.
 *
 * A whole `HandlerThread` rather than a plain executor because `FrameExtractor`
 * builds an `ExoPlayer` internally and must be reached from a single thread —
 * and this container's extractor also touches a `Looper`. A handler thread
 * satisfies both readings, which is why the M0 spike was run against exactly
 * this arrangement before anything was built on top of it.
 */
@OptIn(UnstableApi::class)
class ExtractLane(context: Context, private val sources: ThumbnailSources, index: Int) {
    private val appContext = context.applicationContext
    private val thread = HandlerThread("thumbnail-$index").apply { start() }

    /** Confined to [thread]: the extractor is created, used and closed through it. */
    val dispatcher = Handler(thread.looper).asCoroutineDispatcher()

    /**
     * Blocking. **Must be called on [dispatcher]** — the extractor is built and
     * torn down inside, and all three have to happen on the same thread.
     */
    fun extractBlocking(key: ThumbnailKey): Bitmap? {
        val item = MediaItem.fromUri(KrystallosUri.of(key.serverId, key.path))
        val frame = FrameExtractor.Builder(appContext, item)
            .setMediaSourceFactory(
                ProgressiveMediaSource.Factory(
                    KrystallosDataSource.Factory(sources.forServer(key.serverId), WINDOW_BYTES),
                ),
            )
            .build()
            .use { it.thumbnail.get(EXTRACT_TIMEOUT_SECS, TimeUnit.SECONDS).bitmap }
            ?: return null
        return shrink(frame)
    }

    /**
     * Scale a decoded frame down to something a list row can use.
     *
     * `getThumbnail` hands back a **full-resolution** frame, so an untouched
     * 1080p film would put a ~120 KB JPEG on disk and, worse, an 8 MB
     * `Bitmap` in the memory cache — for a picture drawn 64 dp wide. Eight of
     * those would exhaust the cache and make scrolling re-extract constantly.
     *
     * Done here, on the lane, so the full-size bitmap never leaves this thread.
     */
    private fun shrink(frame: Bitmap): Bitmap {
        if (frame.width <= TARGET_WIDTH) return frame
        val height = (frame.height.toLong() * TARGET_WIDTH / frame.width).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(frame, TARGET_WIDTH, height, /* filter = */ true)
        if (scaled !== frame) frame.recycle()
        return scaled
    }

    fun close() {
        thread.quitSafely()
    }

    companion object {
        /**
         * Small on purpose. A thumbnail needs a container index and one frame;
         * at the playback window of 1 MiB each row would pull a mebibyte off the
         * network for a picture a few kilobytes large.
         */
        const val WINDOW_BYTES = 256 * 1024

        /**
         * Wide enough for the 64 dp row at any phone density (64 dp is 192 px
         * at 3×, 256 px at 4×) with room to spare, and small enough that a
         * stored JPEG lands around 10–20 KB.
         */
        const val TARGET_WIDTH = 320

        /**
         * A backstop, not the usual path: every kernel operation already has its
         * own 20-second timeout, so a read that hangs surfaces from there first.
         */
        private const val EXTRACT_TIMEOUT_SECS = 30L
    }
}

/**
 * A fixed set of lanes, each running one extraction at a time.
 *
 * Serialising per lane is what keeps `FrameExtractor` on a single thread; the
 * round-robin counter spreads work across them so two rows can be decoded at once.
 */
class LanePool(
    private val lanes: List<ExtractLane>,
    private val sources: ThumbnailSources,
) : FrameSource {
    private val laneLocks = lanes.map { Mutex() }
    private val next = AtomicInteger()

    override suspend fun extract(key: ThumbnailKey): Extraction {
        val index = (next.getAndIncrement() and Int.MAX_VALUE) % lanes.size
        val lane = lanes[index]
        return try {
            laneLocks[index].withLock {
                try {
                    val bitmap = withContext(lane.dispatcher) { lane.extractBlocking(key) }
                    if (bitmap != null) Extraction.Frame(bitmap) else Extraction.Unsupported
                } finally {
                    // Released whatever happened: the film just extracted is not
                    // the one the next extraction will want, and a handle left
                    // open on the server is a handle the server cannot give out.
                    sources.forServer(key.serverId).releaseFile()
                }
            }
        } catch (e: Throwable) {
            val failure = classify(e)
            // A transient failure may be a session that died. Dropping it is
            // what makes the next attempt — which the caller will make, since
            // transient failures are deliberately not remembered — reconnect
            // rather than ask the same dead connection again.
            if (failure == Extraction.Transient) sources.forServer(key.serverId).invalidate()
            failure
        }
    }

    fun close() = lanes.forEach { it.close() }

    /**
     * Sort a failure into "this film cannot be thumbnailed" and "try again later".
     *
     * **The default is [Extraction.Transient]**, which is the safe way round: an
     * unremembered failure costs one wasted extraction next time, whereas a
     * wrongly remembered one leaves that film without a thumbnail until the
     * cache is cleared. Only a failure we can positively blame on the file is
     * written down.
     */
    private fun classify(e: Throwable): Extraction = when (val cause = rootCause(e)) {
        is KernelException.ConnectionLost, is KernelException.NotFound, is IOException ->
            Extraction.Transient
        is ExoPlaybackException ->
            if (cause.errorCode in FILE_PROBLEM_CODES) Extraction.Unsupported else Extraction.Transient
        else -> Extraction.Transient
    }

    private fun rootCause(e: Throwable): Throwable =
        when {
            e is ExecutionException && e.cause != null -> e.cause!!
            else -> e
        }

    private companion object {
        /**
         * Codes that blame the file rather than the connection: the container or
         * its codec is beyond what this device can open. The `IO_*` codes are
         * deliberately absent — those are the ones that come and go.
         */
        val FILE_PROBLEM_CODES = setOf(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        )
    }
}
