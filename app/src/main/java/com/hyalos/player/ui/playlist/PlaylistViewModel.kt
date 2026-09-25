package com.hyalos.player.ui.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hyalos.player.AppContainer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One server's playlist, as the stored paths it holds.
 *
 * Nothing is copied into the ViewModel: the list is a file on disk and this
 * screen is its only writer, so the flow is the whole of the state.
 */
class PlaylistViewModel(
    private val container: AppContainer,
    private val serverId: String,
) : ViewModel() {

    /** `null` until the stored list has been read, so "loading" reads differently from "empty". */
    val entries: StateFlow<List<String>?> = container.playlists.playlist(serverId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Shown under the title, because the list belongs to one server and not to the app. */
    val serverName: StateFlow<String> = container.servers.servers
        .map { servers -> servers.find { it.id == serverId }?.name.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    fun remove(path: String) {
        viewModelScope.launch { container.playlists.remove(serverId, path) }
    }

    fun clear() {
        viewModelScope.launch { container.playlists.clear(serverId) }
    }
}
