package com.hyalos.player.ui.browser

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hyalos.player.AppContainer
import com.hyalos.player.data.BrowserLayout
import com.hyalos.player.data.SortKey
import com.hyalos.player.kernel.RemotePath
import com.hyalos.player.thumbnails.ThumbnailKey
import com.hyalos.player.ui.common.UiError
import com.hyalos.player.ui.common.toUiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import uniffi.krystallos_ffi.DirEntry

/**
 * One directory's listing.
 *
 * Scoped to its own back-stack entry, so returning from a subdirectory shows
 * the cached listing immediately instead of asking the server again.
 *
 * # The raw listing is kept, and re-sorted locally
 *
 * Changing the sort order must not cost a round-trip to the NAS — the directory
 * has not changed, only the view of it. So the entries as the server returned
 * them are held here and re-ordered whenever the sort preference changes.
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

    /** Exactly what the server returned, before filtering or ordering. */
    private val entries = MutableStateFlow<List<DirEntry>?>(null)

    /**
     * Rows or tiles, and the sort order. Both follow the stored preferences, so
     * the choices survive leaving the screen and restarting the app.
     */
    val layout: StateFlow<BrowserLayout> = container.settings.settings
        .map { it.browserLayout }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BrowserLayout.LIST)

    val sortKey: StateFlow<SortKey> = container.settings.settings
        .map { it.sortKey }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SortKey.NAME)

    val sortAscending: StateFlow<Boolean> = container.settings.settings
        .map { it.sortAscending }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    private var job: Job? = null

    init {
        viewModelScope.launch { serverName = container.servers.get(serverId)?.name.orEmpty() }

        // Re-order whenever the listing or the sort preference changes. This is
        // the only place `State.Loaded` is produced, so the two can never
        // disagree about what order the list is in.
        viewModelScope.launch {
            combine(entries, container.settings.settings) { raw, settings ->
                raw?.let { EntrySorting.prepare(it, settings.sortKey, settings.sortAscending, nameOrder) }
            }.collect { sorted -> if (sorted != null) state = State.Loaded(sorted) }
        }

        load()
    }

    fun refresh() = load(refresh = true)

    fun retry() = load()

    fun toggleLayout() {
        viewModelScope.launch {
            container.settings.setBrowserLayout(
                if (layout.value == BrowserLayout.LIST) BrowserLayout.GRID else BrowserLayout.LIST,
            )
        }
    }

    fun setSortKey(key: SortKey) {
        viewModelScope.launch { container.settings.setSortKey(key) }
    }

    fun setSortAscending(ascending: Boolean) {
        viewModelScope.launch { container.settings.setSortAscending(ascending) }
    }

    /**
     * The thumbnail for [item], or `null` if there is none to show.
     *
     * Called from the item's composition, so it runs only while that item is on
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
                entries.value = container.sessions.list(serverId, path)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Cleared so a later settings change cannot resurrect the stale
                // listing over the error the user is looking at.
                entries.value = null
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
