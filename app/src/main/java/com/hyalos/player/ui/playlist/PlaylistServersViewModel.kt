package com.hyalos.player.ui.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hyalos.player.AppContainer
import com.hyalos.player.data.ServerConfig
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * The servers, with a note of which of them have anything in their playlist.
 *
 * The lists are per server, so this is the step between the tab and one of them.
 */
class PlaylistServersViewModel(container: AppContainer) : ViewModel() {

    /** One row: the server, and whether its playlist has anything in it. */
    data class Choice(val server: ServerConfig, val hasEntries: Boolean)

    /** `null` until both stores have been read, so "loading" differs from "none". */
    val choices: StateFlow<List<Choice>?> =
        combine(container.servers.servers, container.playlists.all) { servers, playlists ->
            servers.map { Choice(it, playlists[it.id].orEmpty().isNotEmpty()) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
