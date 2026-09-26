package com.hyalos.player.ui.player

import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.hyalos.player.AppContainer
import com.hyalos.player.data.PlaybackOrientation
import com.hyalos.player.data.VideoScale
import com.hyalos.player.info.DecodeFacts
import com.hyalos.player.info.InfoSection
import com.hyalos.player.info.decoderFact
import com.hyalos.player.info.infoSections
import com.hyalos.player.info.toProbedMedia
import com.hyalos.player.kernel.RemotePath
import com.hyalos.player.playback.KrystallosDataSource
import com.hyalos.player.playback.KrystallosUri
import com.hyalos.player.playback.PlaybackConnection
import com.hyalos.player.ui.browser.BrowserItem
import com.hyalos.player.ui.browser.EntrySorting
import com.hyalos.player.ui.common.dateText
import com.hyalos.player.ui.common.sizeText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Owns the player, so that rotating the screen neither restarts playback nor
 * reconnects to the server.
 */
@OptIn(UnstableApi::class)
class PlayerViewModel(
    private val container: AppContainer,
    private val serverId: String,
    private val path: String,
    /** Whether the queue comes from the server's playlist rather than the folder. */
    private val fromPlaylist: Boolean = false,
) : ViewModel() {

    private val _title = MutableStateFlow(titleOf(path))

    /**
     * The film playing **now**.
     *
     * Observed from the player rather than taken from the route once. The queue
     * moves on its own, and a title still naming the film that was tapped would
     * be wrong for the whole of the next one — which is worse than having no
     * title at all.
     */
    val title: StateFlow<String> = _title.asStateFlow()

    /**
     * A film's name, without the extension.
     *
     * It is a title rather than a file name: ".mkv" says nothing about which
     * film it is and is the noisiest part of a long name. The folder is left out
     * for the opposite of the reason the browser's rows show it — there a list
     * has to tell two folders' worth of episodes apart; here there is one film.
     */
    private fun titleOf(remotePath: String): String = RemotePath.name(remotePath)
        .substringBeforeLast('.')
        .ifBlank { RemotePath.name(remotePath) }

    /** A session of its own; see SessionManager for why playback does not share one. */
    private val connection = PlaybackConnection(
        connect = { container.sessions.connectDedicated(serverId) },
        scope = container.appScope,
    )

    val player: ExoPlayer = ExoPlayer.Builder(container.appContext)
        // Progressive, not the default factory: every source here is a plain
        // file, never a manifest.
        .setMediaSourceFactory(ProgressiveMediaSource.Factory(KrystallosDataSource.Factory(connection)))
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            /* handleAudioFocus = */ true,
        )
        // Pause when headphones are unplugged rather than blaring from the speaker.
        .setHandleAudioBecomingNoisy(true)
        .build()
        .apply {
            // The tapped item starts immediately, and the rest of the folder is
            // added around it once the listing arrives. Waiting for the listing
            // first would put a round-trip between the tap and the first frame,
            // which on a slow link is seconds of nothing happening.
            setMediaItem(MediaItem.fromUri(KrystallosUri.of(serverId, path)))
            playWhenReady = true
            prepare()
        }

    // ---------------------------------------------------------------------
    // What the player is doing
    // ---------------------------------------------------------------------
    //
    // Declared above `init` because Kotlin initialises in declaration order and
    // `init` attaches the listener below.

    /** The server's display name, read once — the info dialog names it. */
    private var serverName = ""

    /**
     * The file actually playing, which is not the one that was tapped once the
     * queue moves on. The info dialog describes *this* file — name, path and
     * tracks all have to be about the same one, and the tracks come from the
     * player, so they follow the queue.
     */
    private var playingPath = path

    /**
     * The decoders the player opened, caught as the callbacks arrive.
     *
     * **There is no way to ask.** Media3 reports the decoder only through
     * `AnalyticsListener`, and only at the moment it is initialised — the
     * player has no `getCurrentDecoder()` — so the names have to be kept as they
     * go past. The mime type comes from the format callbacks instead, which the
     * decoder callbacks do not carry.
     */
    private var videoDecoderName: String? = null
    private var audioDecoderName: String? = null
    private var videoMimeType: String? = null
    private var audioMimeType: String? = null

    private val decoderWatcher = object : AnalyticsListener {
        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            videoDecoderName = decoderName
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            audioDecoderName = decoderName
        }

        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            videoMimeType = format.sampleMimeType
        }

        override fun onAudioInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            audioMimeType = format.sampleMimeType
        }
    }

    init {
        // From the start, not when the dialog opens: the decoder is initialised
        // once, at the beginning, and says nothing again.
        player.addAnalyticsListener(decoderWatcher)
        viewModelScope.launch { serverName = container.servers.get(serverId)?.name.orEmpty() }

        // What is playing stops being what was tapped the moment the queue moves
        // on. Media3 says when that happens; the title follows.
        player.addListener(
            object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    val playing = mediaItem?.localConfiguration?.uri?.path ?: return
                    _title.value = titleOf(playing)
                    playingPath = playing
                }
            },
        )

        if (path.isNotEmpty()) {
            viewModelScope.launch {
                if (fromPlaylist) {
                    // A hand-built list is a request to play that list through,
                    // so it does not consult the setting at all — not on the way
                    // in, and not while it plays.
                    extendPlaylist()
                } else {
                    // Watched rather than read once: the setting is a tap away
                    // from the player, and one that only took effect on the next
                    // film would look broken from here.
                    container.settings.settings
                        .map { it.autoPlayNext }
                        .distinctUntilChanged()
                        .collect { enabled -> applyAutoPlayNext(enabled) }
                }
            }
        }
    }

    /**
     * Follow the setting while the film plays, not only when it starts.
     *
     * Turning it off drops the film's neighbours, so it plays to its end and
     * stops — which is what the setting means, and what used to happen only if
     * it had been off when the film was opened.
     */
    private suspend fun applyAutoPlayNext(enabled: Boolean) {
        if (enabled) {
            // Only when nothing is queued: extending twice would list the folder
            // twice.
            if (player.mediaItemCount <= 1) extendPlaylist()
            return
        }
        // The tail first: removing what follows does not move the current item,
        // where removing what precedes it would.
        val current = player.currentMediaItemIndex
        if (player.mediaItemCount > current + 1) {
            player.removeMediaItems(current + 1, player.mediaItemCount)
        }
        if (current > 0) player.removeMediaItems(0, current)
    }

    /**
     * Add the queue around the film that is already playing.
     *
     * Two sources. A queue the user built by hand is a request to play that
     * queue, in that order, so it is used as it stands. Otherwise the folder is
     * the queue — whether there should be one at all is [applyAutoPlayNext]'s
     * decision, not this function's.
     */
    private suspend fun extendPlaylist() {
        if (fromPlaylist) {
            // The order is the point of a hand-built list, so it is not sorted.
            // Matched by path rather than by name: it is the exact string that
            // was stored, and two folders may hold the same file name.
            val entries = container.playlists.playlist(serverId).first()
            val index = entries.indexOf(path)
            if (index < 0) return
            addAround(index, entries.map { MediaItem.fromUri(KrystallosUri.of(serverId, it)) })
            return
        }

        val settings = container.settings.settings.first()
        val directory = RemotePath.parent(path) ?: RemotePath.ROOT
        val entries = try {
            container.sessions.list(serverId, directory)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The film is already playing; a missing queue is not worth
            // interrupting it for.
            return
        }

        val playable = EntrySorting.playableInOrder(
            entries = entries,
            key = settings.sortKey,
            ascending = settings.sortAscending,
            nameOrder = EntrySorting.systemOrder,
        )
        val current = RemotePath.name(path)
        val index = playable.indexOfFirst { it.name == current }
        if (index < 0) return // The server listed something other than what we opened.

        val items = playable.map { MediaItem.fromUri(KrystallosUri.of(serverId, RemotePath.join(directory, it.name))) }
        addAround(index, items)
    }

    /**
     * Put [items] either side of the one at [index], which is already playing.
     *
     * Inserted rather than replaced: `setMediaItems` would restart playback, and
     * the point is that this arrives *after* the film has started. Media3 keeps
     * the current item current as neighbours are added, so the index shifts
     * rather than the playback.
     *
     * The `play` at the end covers the film having ended while the queue was in
     * flight: adding items to a finished playlist does not restart it, and the
     * user would be left looking at the end of a film with a full queue behind it.
     */
    private fun addAround(index: Int, items: List<MediaItem>) {
        val wasPlaying = player.isPlaying
        player.addMediaItems(0, items.subList(0, index))
        player.addMediaItems(items.subList(index + 1, items.size))
        if (wasPlaying && !player.isPlaying) player.play()
    }

    /**
     * How the picture is fitted to the screen.
     *
     * Observed rather than read once, so changing it in settings takes effect
     * without leaving the player.
     */
    val videoScale: StateFlow<VideoScale> = container.settings.settings
        .map { it.videoScale }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VideoScale.FIT)

    /** Which way up the player opens. */
    val initialOrientation: StateFlow<PlaybackOrientation> = container.settings.settings
        .map { it.initialOrientation }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlaybackOrientation.LANDSCAPE)

    /**
     * The direction picked with the rotate button, for this playback only.
     *
     * Kept here rather than in the screen's composition, which cannot hold it:
     * covering the player with the settings page destroys that composition — the
     * view tree goes with it, measured — so a `remember`ed value would be lost on
     * the way back, and the film would be the wrong way up again. This ViewModel
     * survives that, and is itself cleared when the player is popped, which is
     * exactly "this playback and no further".
     */
    private val _orientationOverride = MutableStateFlow<PlaybackOrientation?>(null)
    val orientationOverride: StateFlow<PlaybackOrientation?> = _orientationOverride.asStateFlow()

    /**
     * Turn the picture the other way.
     *
     * [currentlyLandscape] is read off the device at the moment of the tap rather
     * than remembered, so that the button means "the other one from what I am
     * looking at now" however the screen got this way.
     */
    fun toggleOrientation(currentlyLandscape: Boolean) {
        _orientationOverride.value =
            if (currentlyLandscape) PlaybackOrientation.PORTRAIT else PlaybackOrientation.LANDSCAPE
    }

    /** The rows of the info dialog, or `null` when it is closed. */
    var info by mutableStateOf<List<InfoSection>?>(null)
        private set

    /**
     * Pause, and lay out what the file and the player between them know.
     *
     * Paused on purpose, and **left** paused when it closes — the same rule as
     * opening the playback settings. Two ways to pause and only one of them
     * resuming would be a rule nobody could remember.
     *
     * Tracks come from the running player rather than a second probe of the
     * file: no round trip, and it describes what is actually playing — including
     * whether this device can handle each track, which a file cannot say.
     */
    fun showInfo() {
        player.pause()
        val durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        val tracks = player.currentTracks.takeIf { player.isCommandAvailable(Player.COMMAND_GET_TRACKS) }
        info = infoSections(
            // Only the name is known here: the player route carries a server and
            // a path, not a size or timestamps, and a round trip for two lines
            // nobody opened this dialog for is not worth it. The name keeps its
            // extension, as the browser's does — unlike the title above, which
            // drops it for the screen.
            item = BrowserItem(
                name = RemotePath.name(playingPath),
                kind = BrowserItem.Kind.VIDEO,
                size = null,
                modifiedMs = null,
            ),
            serverName = serverName,
            path = playingPath,
            media = tracks?.toProbedMedia(durationMs),
            formatSize = { sizeText(container.appContext, it) },
            formatDate = { dateText(container.appContext, it) },
            decode = DecodeFacts(
                video = decoderFact(videoDecoderName, videoMimeType),
                audio = decoderFact(audioDecoderName, audioMimeType),
                droppedFrames = player.videoDecoderCounters?.droppedBufferCount,
            ),
        )
    }

    fun dismissInfo() {
        info = null
    }

    /**
     * Give a finished film something to draw again.
     *
     * Covering the player with another screen destroys its composition, and the
     * surface with it; coming back builds a new one. For a film that is still
     * going, the decoder is holding the current frame and the new surface draws
     * it — measured, not assumed. For one that has **finished**, there is no such
     * frame: the decoder reached the end of the stream and let it go, and a new
     * surface does not make it produce another. The picture stays black until
     * something plays, and nothing does.
     *
     * A seek makes it decode and draw again. The position to seek to is the
     * start, because that is where this player is going to play from anyway — a
     * finished ExoPlayer restarts on `play`. Seeking back from the end also keeps
     * that true, which the other candidate does not: land a hair *before* the end
     * and the player no longer counts as finished, so pressing play would play
     * the last instant and stop, looking like a dead button.
     */
    fun redrawIfFinished() {
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
    }

    /** After an error, try again from where it stopped. */
    fun retry() {
        player.prepare()
    }

    override fun onCleared() {
        // Removal wants the very same instance — Media3 keys on identity — which
        // is why the listener is a field and not built inline in `init`.
        player.removeAnalyticsListener(decoderWatcher)
        // The player first: releasing it interrupts the loader thread, so
        // nothing starts a new read on the connection while it is closing. A
        // read already inside the kernel runs on to its timeout — cancellation
        // does not cross the FFI — and the disconnect simply queues behind it.
        player.release()
        container.appScope.launch(Dispatchers.IO) { connection.close() }
    }
}
