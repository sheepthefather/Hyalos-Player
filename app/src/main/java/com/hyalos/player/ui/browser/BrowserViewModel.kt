package com.hyalos.player.ui.browser

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hyalos.player.AppContainer
import com.hyalos.player.ui.common.UiError
import com.hyalos.player.ui.common.toUiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * One directory's listing.
 *
 * Scoped to its own back-stack entry, so returning from a subdirectory shows
 * the cached listing immediately instead of asking the server again.
 */
class BrowserViewModel(
    private val container: AppContainer,
    val serverId: String,
    val path: String,
) : ViewModel() {

    sealed interface State {
        data object Loading : State
        data class Loaded(val items: List<BrowserItem>) : State
        data class Failed(val error: UiError) : State
    }

    var state by mutableStateOf<State>(State.Loading)
        private set

    /** A pull-to-refresh in progress, over an already-loaded list. */
    var refreshing by mutableStateOf(false)
        private set

    /** For the title and the root breadcrumb. */
    var serverName by mutableStateOf("")
        private set

    private var job: Job? = null

    init {
        viewModelScope.launch { serverName = container.servers.get(serverId)?.name.orEmpty() }
        load()
    }

    fun refresh() = load(refresh = true)

    fun retry() = load()

    private fun load(refresh: Boolean = false) {
        job?.cancel()
        if (refresh && state is State.Loaded) refreshing = true else state = State.Loading
        job = viewModelScope.launch {
            try {
                val entries = container.sessions.list(serverId, path)
                state = State.Loaded(EntrySorting.prepare(entries, nameOrder))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = State.Failed(e.toUiError())
            } finally {
                refreshing = false
            }
        }
    }

    private companion object {
        /** Building a collator is not free; one serves every directory. */
        val nameOrder by lazy { EntrySorting.systemNameOrder() }
    }
}
