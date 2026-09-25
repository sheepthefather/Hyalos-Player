package com.hyalos.player.ui.browser

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hyalos.player.AppContainer
import com.hyalos.player.kernel.RemotePath
import com.hyalos.player.thumbnails.ThumbnailKey
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

    /**
     * The thumbnail for [item], or `null` if there is none to show.
     *
     * Called from the row's composition, so it runs only while that row is on
     * screen and is cancelled when it scrolls away. The key carries the size and
     * modification time, so a file that is replaced gets a fresh frame rather
     * than the old film's.
     */
    suspend fun thumbnailFor(item: BrowserItem): android.graphics.Bitmap? {
        if (item.kind != BrowserItem.Kind.VIDEO) return null
        return container.thumbnails.load(
            ThumbnailKey(
                serverId = serverId,
                path = RemotePath.join(path, item.name),
                size = item.size,
                modifiedMs = item.modifiedMs,
            ),
        )
    }

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
