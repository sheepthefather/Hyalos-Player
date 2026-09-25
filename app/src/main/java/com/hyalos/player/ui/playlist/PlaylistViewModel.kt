package com.hyalos.player.ui.playlist

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hyalos.player.AppContainer
import com.hyalos.player.data.BrowserLayout
import com.hyalos.player.kernel.RemotePath
import com.hyalos.player.thumbnails.ThumbnailKey
import com.hyalos.player.ui.browser.BrowserItem
import com.hyalos.player.ui.browser.EntrySorting
import com.hyalos.player.ui.common.EntryRow
import com.hyalos.player.ui.common.Selection
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One server's playlist, as a list to look at and to trim.
 *
 * The entries come from a local file rather than the network, so none of the
 * browser's retry machinery is here: a read cannot fail halfway, and there is
 * nothing to refresh. Thumbnails are the one thing that still needs the server,
 * and they degrade to an icon without it.
 */
class PlaylistViewModel(
    private val container: AppContainer,
    private val serverId: String,
) : ViewModel() {

    sealed interface State {
        data object Loading : State

        /** [paths] is the order they play in; [rows] is the same list as drawn. */
        data class Loaded(val rows: List<EntryRow>, val paths: List<String>) : State
    }

    var state by mutableStateOf<State>(State.Loading)
        private set

    /** Shown in the title — the list belongs to a server, not to the app. */
    val serverName: StateFlow<String> = container.servers.servers
        .map { servers -> servers.find { it.id == serverId }?.name.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    /**
     * Rows or tiles, from the same stored preference the browser reads.
     *
     * Shared rather than separate: it is one answer to "how do I like looking at
     * a list of films", and switching to tiles here should not leave the
     * directory view disagreeing. The initial value matches the browser's, or
     * the first frame would flash the other layout.
     */
    val layout: StateFlow<BrowserLayout> = container.settings.settings
        .map { it.browserLayout }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BrowserLayout.LIST)

    var selection by mutableStateOf(Selection.NONE)
        private set

    val selecting: Boolean get() = selection.active

    /** Paths, which is what identifies an entry here — two folders may share a name. */
    val selected: Set<String> get() = selection.ids

    /** A removal waiting on confirmation. */
    var confirmingRemove by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch {
            container.playlists.playlist(serverId).collect { paths ->
                // Keep the selection honest against the list it acts on: an entry
                // this screen just removed must not stay counted.
                selection = selection.prune(paths.toSet())
                state = State.Loaded(paths.map { it.toRow() }, paths)
            }
        }
    }

    fun onTap(path: String, play: () -> Unit) {
        if (selecting) toggleSelection(path) else play()
    }

    fun onLongPress(path: String) {
        selection = selection.select(path)
    }

    fun toggleSelection(path: String) {
        selection = selection.toggle(path)
    }

    fun clearSelection() {
        selection = Selection.NONE
    }

    /** The select-all action, so emptying a long list is two taps rather than fifty. */
    fun selectAll() {
        val loaded = state as? State.Loaded ?: return
        selection = Selection.all(loaded.paths)
    }

    fun askRemove() {
        if (selection.ids.isNotEmpty()) confirmingRemove = true
    }

    fun cancelRemove() {
        confirmingRemove = false
    }

    fun confirmRemove() {
        val doomed = selection.ids.toList()
        confirmingRemove = false
        viewModelScope.launch {
            container.playlists.removeAll(serverId, doomed)
            clearSelection()
        }
    }

    fun toggleLayout() {
        viewModelScope.launch {
            container.settings.setBrowserLayout(
                if (layout.value == BrowserLayout.LIST) BrowserLayout.GRID else BrowserLayout.LIST,
            )
        }
    }

    suspend fun loadThumbnail(key: ThumbnailKey): android.graphics.Bitmap? =
        container.thumbnails.load(key)

    /**
     * One entry as the list draws it.
     *
     * The thumbnail key carries no size or modification time, because a playlist
     * holds only paths. It therefore does not match the key the browser builds
     * for the same film, and the two pages keep separate cache entries — the
     * alternative is a `stat` per entry, and this page is meant to open without
     * the server at all. See `ARCHITECTURE.md`.
     */
    private fun String.toRow(): EntryRow {
        val name = RemotePath.name(this)
        val kind = EntrySorting.kindOf(name)
        return EntryRow(
            id = this,
            name = name,
            // Where it lives, not a size and a date: the whole point of this list
            // is that its entries come from different folders.
            detail = RemotePath.parent(this) ?: RemotePath.ROOT,
            icon = EntrySorting.iconFor(kind),
            thumbnail = ThumbnailKey(serverId, this, size = null, modifiedMs = null)
                .takeIf { kind == BrowserItem.Kind.VIDEO },
        )
    }
}
