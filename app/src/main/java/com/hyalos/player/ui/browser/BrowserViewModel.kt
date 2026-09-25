package com.hyalos.player.ui.browser

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hyalos.player.AppContainer
import com.hyalos.player.data.BrowserLayout
import com.hyalos.player.data.SortKey
import com.hyalos.player.files.ClipboardContent
import com.hyalos.player.files.ClipboardMode
import com.hyalos.player.files.FileOperations
import com.hyalos.player.files.OperationResult
import com.hyalos.player.files.RemoteItem
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

    // ---------------------------------------------------------------------
    // Selection
    // ---------------------------------------------------------------------

    /** What a file operation is; also its label in the progress indicator. */
    enum class Operation { RENAME, DELETE, COPY, MOVE }

    data class Report(val operation: Operation, val result: OperationResult)

    /** A delete waiting on confirmation, and how much it would actually remove. */
    data class DeletePrompt(val items: List<RemoteItem>, val total: Int, val truncated: Boolean)

    /** What is selected. See [Selection] for why the state lives in one object. */
    var selection by mutableStateOf(Selection.NONE)
        private set

    /** In selection mode a tap selects; otherwise it opens. */
    val selecting: Boolean get() = selection.active

    /** Names of the selected entries, exactly as the listing reports them. */
    val selected: Set<String> get() = selection.names

    /** The operation in flight, or `null`. */
    var busy by mutableStateOf<Operation?>(null)
        private set

    /** The last finished operation, for the report at the bottom of the screen. */
    var report by mutableStateOf<Report?>(null)
        private set

    var renaming by mutableStateOf<BrowserItem?>(null)
        private set

    var deletePrompt by mutableStateOf<DeletePrompt?>(null)
        private set

    val clipboard: StateFlow<ClipboardContent?> = container.clipboard.content

    /**
     * A long press selects the item and enters selection mode.
     *
     * One mode covers both requests — acting on a single file and acting on
     * several — because they are the same intent differing only in how many
     * items are checked. Rename is offered only while exactly one is selected,
     * which is where the two cases genuinely diverge.
     */
    fun onLongPress(item: BrowserItem) {
        selection = selection.select(item.name)
    }

    fun onTap(item: BrowserItem, open: () -> Unit) {
        if (selecting) toggleSelection(item.name) else open()
    }

    fun toggleSelection(name: String) {
        selection = selection.toggle(name)
    }

    fun clearSelection() {
        selection = Selection.NONE
    }

    // ---------------------------------------------------------------------
    // File operations
    // ---------------------------------------------------------------------

    fun startRename(item: BrowserItem) {
        renaming = item
    }

    fun cancelRename() {
        renaming = null
    }

    fun commitRename(newName: String) {
        val item = renaming ?: return
        renaming = null
        val target = remoteOf(item)
        run(Operation.RENAME) {
            val result = attempt(target) { container.files.rename(target, newName) }
            if (result.succeeded > 0) {
                // Done with it: leaving the item selected would keep the toolbar
                // up for an action that has already happened.
                clearSelection()
                // Re-read the directory: the server has the new name, and a
                // listing still showing the old one looks like the rename failed.
                load(refresh = true)
            }
            result
        }
    }

    /**
     * Ask to delete, counting first.
     *
     * The count is what turns "delete Series 3" into "delete 47 items", which is
     * the difference between a considered decision and an accident.
     */
    fun askDelete() {
        val items = selectedItems()
        if (items.isEmpty()) return
        run(null) {
            val total = container.files.countContents(items)
            deletePrompt = DeletePrompt(
                items = items,
                total = total,
                truncated = total >= FileOperations.COUNT_LIMIT,
            )
            null
        }
    }

    fun cancelDelete() {
        deletePrompt = null
    }

    fun confirmDelete() {
        val prompt = deletePrompt ?: return
        deletePrompt = null
        run(Operation.DELETE) {
            val result = container.files.delete(prompt.items)
            clearSelection()
            load(refresh = true)
            result
        }
    }

    fun copySelected() {
        container.clipboard.copy(selectedItems())
        clearSelection()
    }

    fun cutSelected() {
        container.clipboard.cut(selectedItems())
        clearSelection()
    }

    fun paste() {
        val content = container.clipboard.content.value ?: return
        // The current directory is the destination; the session root has no name
        // of its own, so it gets a placeholder for error messages.
        val target = RemoteItem(
            serverId = serverId,
            path = path,
            name = RemotePath.name(path).ifEmpty { "/" },
            isDirectory = true,
        )
        val operation = if (content.mode == ClipboardMode.CUT) Operation.MOVE else Operation.COPY
        run(operation) {
            val result = if (content.mode == ClipboardMode.CUT) {
                container.files.moveInto(content.items, target)
            } else {
                container.files.copyInto(content.items, target)
            }
            container.clipboard.consumeIfCut()
            load(refresh = true)
            result
        }
    }

    fun dismissReport() {
        report = null
    }

    /** Run one operation at a time, reporting whatever it managed to do. */
    private fun run(operation: Operation?, block: suspend () -> OperationResult?) {
        if (busy != null) return
        viewModelScope.launch {
            busy = operation
            try {
                val result = block()
                // The operation itself may have set a report (counting does not).
                if (result != null) report = Report(operation ?: Operation.COPY, result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report = Report(operation ?: Operation.COPY, OperationResult().also { it.recordFailure("", e) })
            } finally {
                busy = null
            }
        }
    }

    /** One item's operation, expressed as a result so the report is uniform. */
    private suspend fun attempt(target: RemoteItem, block: suspend () -> Unit): OperationResult {
        val result = OperationResult()
        try {
            block()
            result.succeeded++
        } catch (e: Exception) {
            result.recordFailure(target.name, e)
        }
        return result
    }

    private fun remoteOf(item: BrowserItem) = RemoteItem(
        serverId = serverId,
        path = RemotePath.join(path, item.name),
        name = item.name,
        isDirectory = item.kind == BrowserItem.Kind.DIRECTORY,
    )

    private fun selectedItems(): List<RemoteItem> =
        (state as? State.Loaded)?.items.orEmpty()
            .filter { it.name in selected }
            .map { remoteOf(it) }

    private var job: Job? = null

    init {
        viewModelScope.launch { serverName = container.servers.get(serverId)?.name.orEmpty() }

        // Re-order whenever the listing or the sort preference changes. This is
        // the only place `State.Loaded` is produced, so the two can never
        // disagree about what order the list is in.
        viewModelScope.launch {
            combine(entries, container.settings.settings) { raw, settings ->
                raw?.let { EntrySorting.prepare(it, settings.sortKey, settings.sortAscending, nameOrder) }
            }.collect { sorted ->
                if (sorted != null) {
                    // A selection holds names, and names stop existing — after a
                    // rename, a delete, or a change made by somebody else. Left
                    // alone they would have the toolbar counting items that are
                    // not on screen and cannot be acted on.
                    selection = selection.prune(sorted.mapTo(mutableSetOf()) { it.name })
                    state = State.Loaded(sorted)
                }
            }
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
        /** Shared with the player, so a playlist orders names the same way. */
        val nameOrder = EntrySorting.systemOrder
    }
}
