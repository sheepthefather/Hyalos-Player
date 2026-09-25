package com.hyalos.player.ui.player

import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.hyalos.player.AppContainer
import com.hyalos.player.data.VideoScale
import com.hyalos.player.kernel.RemotePath
import com.hyalos.player.playback.KrystallosDataSource
import com.hyalos.player.playback.KrystallosUri
import com.hyalos.player.playback.PlaybackConnection
import com.hyalos.player.ui.browser.EntrySorting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    serverId: String,
    path: String,
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

    init {
        // What is playing stops being what was tapped the moment the queue moves
        // on. Media3 says when that happens; the title follows.
        player.addListener(
            object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    val playing = mediaItem?.localConfiguration?.uri?.path ?: return
                    _title.value = titleOf(playing)
                }
            },
        )

        if (path.isNotEmpty()) {
            viewModelScope.launch { extendPlaylist(serverId, path) }
        }
    }

    /**
     * Add the queue around the film that is already playing.
     *
     * Two sources. A queue the user built by hand is a request to play that
     * queue, in that order, so it is used as it stands — the auto-play-next
     * setting has nothing to say about it. Otherwise the folder is the queue,
     * and that setting is exactly what decides whether there is one: with it off
     * nothing is added, the playlist holds one item, and the film simply ends.
     */
    private suspend fun extendPlaylist(serverId: String, path: String) {
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
        if (!settings.autoPlayNext) return

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

    /** After an error, try again from where it stopped. */
    fun retry() {
        player.prepare()
    }

    override fun onCleared() {
        // The player first: releasing it interrupts the loader thread, so
        // nothing starts a new read on the connection while it is closing. A
        // read already inside the kernel runs on to its timeout — cancellation
        // does not cross the FFI — and the disconnect simply queues behind it.
        player.release()
        container.appScope.launch(Dispatchers.IO) { connection.close() }
    }
}
