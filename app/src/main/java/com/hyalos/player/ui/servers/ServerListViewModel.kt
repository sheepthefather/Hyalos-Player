package com.hyalos.player.ui.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hyalos.player.AppContainer
import com.hyalos.player.data.ServerConfig
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ServerListViewModel(private val container: AppContainer) : ViewModel() {

    /** `null` until the stored list has been read, so the UI can tell "loading" from "empty". */
    val servers: StateFlow<List<ServerConfig>?> = container.servers.servers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun delete(server: ServerConfig) {
        viewModelScope.launch {
            container.servers.delete(server.id)
            container.credentials.remove(server.id)
            container.sessions.invalidate(server.id)
        }
    }
}
