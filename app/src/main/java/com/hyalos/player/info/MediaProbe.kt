package com.hyalos.player.info

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.inspector.MetadataRetriever
import com.hyalos.player.playback.KrystallosDataSource
import com.hyalos.player.playback.KrystallosUri
import com.hyalos.player.playback.ReaderSource
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** One track of a file, reduced to the parts worth showing. */
data class TrackInfo(
    val kind: Kind,
    /** `video/avc`, `audio/mp4a-latm` — straight out of the container. */
    val mimeType: String?,
    /** The codec string, `avc1.640028`. Profile and level live in here. */
    val codecs: String?,
    val width: Int?,
    val height: Int?,
    val frameRate: Float?,
    /** Null when the container does not state one; an estimate is the caller's to make. */
    val bitrate: Int?,
    val channelCount: Int?,
    val sampleRate: Int?,
    val language: String?,
) {
    enum class Kind { VIDEO, AUDIO, TEXT, OTHER }
}

/** What a file says about itself. */
data class ProbedMedia(
    val containerMimeType: String?,
    /** Null when the file does not say — a stream still opening, not a broken file. */
    val durationMs: Long?,
    val tracks: List<TrackInfo>,
)

/**
 * Reads a file's track formats without playing it.
 *
 * `MetadataRetriever` rather than Media3's extractors directly: it is the same
 * machinery ExoPlayer opens a file with, stopped before anything is decoded, and
 * its `Builder` takes a `MediaSource.Factory` — the very one the thumbnails
 * already build over `KrystallosDataSource`. Reaching the same `Format`s by
 * writing an `ExtractorOutput` would be a few hundred lines for no new
 * information.
 *
 * A probe is rare (one dialog) and short, so it runs on a thread of its own that
 * it starts and quits rather than on a pool. It has to be **one** thread for the
 * retriever's whole life — the rule `ExtractLane` documents at length — and a
 * pool offers no such promise.
 */
class MediaProbe(private val context: Context) {

    /**
     * Takes a [ReaderSource] rather than a server id, so the caller owns the
     * connection: a caller that has just made one for this probe can close it
     * after, and a test can hand over a session it built itself.
     */
    suspend fun probe(source: ReaderSource, serverId: String, path: String): ProbedMedia {
        val thread = HandlerThread("media-probe").apply { start() }
        return try {
            withContext(Handler(thread.looper).asCoroutineDispatcher()) { read(source, serverId, path) }
        } finally {
            thread.quitSafely()
        }
    }

    @OptIn(UnstableApi::class)
    private fun read(source: ReaderSource, serverId: String, path: String): ProbedMedia {
        val retriever = MetadataRetriever.Builder(
            context,
            MediaItem.fromUri(KrystallosUri.of(serverId, path)),
        )
            .setMediaSourceFactory(
                ProgressiveMediaSource.Factory(KrystallosDataSource.Factory(source, WINDOW_BYTES)),
            )
            .build()

        return retriever.use { retriever ->
            val groups = retriever.retrieveTrackGroups().get(TIMEOUT_SECS, TimeUnit.SECONDS)
            // A duration we cannot read is not a failed probe: the tracks are the
            // point, and the rest of the dialog stands without it.
            val durationUs = runCatching {
                retriever.retrieveDurationUs().get(TIMEOUT_SECS, TimeUnit.SECONDS)
            }.getOrNull()
            groups.toProbedMedia(durationUs?.takeIf { it > 0 }?.div(1000))
        }
    }

    companion object {
        /**
         * The window the thumbnails use as well. A container index is what is
         * being read here, not the film — a mebibyte per probe would be most of
         * a short film.
         */
        const val WINDOW_BYTES = 256 * 1024

        /** A backstop; the kernel's own operations time out at 20 seconds first. */
        private const val TIMEOUT_SECS = 30L
    }
}

@OptIn(UnstableApi::class)
private fun TrackGroupArray.toProbedMedia(durationMs: Long?): ProbedMedia {
    var container: String? = null
    // Built with a plain loop, not `buildList`: its receiver is the list, which
    // would shadow this array and silently turn `get(index)` into a list lookup.
    val tracks = ArrayList<TrackInfo>(length)
    for (index in 0 until length) {
        val group = get(index)
        // One format per group. A group can hold several when the source is
        // adaptive; a file on a NAS is not.
        val format = group.getFormat(0)
        if (container == null) container = format.containerMimeType
        tracks += format.toTrackInfo(group.type)
    }
    return ProbedMedia(containerMimeType = container, durationMs = durationMs, tracks = tracks)
}

private fun Format.toTrackInfo(type: Int) = TrackInfo(
    kind = when (type) {
        C.TRACK_TYPE_VIDEO -> TrackInfo.Kind.VIDEO
        C.TRACK_TYPE_AUDIO -> TrackInfo.Kind.AUDIO
        C.TRACK_TYPE_TEXT -> TrackInfo.Kind.TEXT
        else -> TrackInfo.Kind.OTHER
    },
    mimeType = sampleMimeType,
    codecs = codecs,
    // `NO_VALUE` is -1 for every one of these, and so is "the container did not
    // say" — which is what null means from here on.
    width = width.takeIf { it > 0 },
    height = height.takeIf { it > 0 },
    frameRate = frameRate.takeIf { it > 0f },
    bitrate = bitrate.takeIf { it > 0 },
    channelCount = channelCount.takeIf { it > 0 },
    sampleRate = sampleRate.takeIf { it > 0 },
    language = language,
)
