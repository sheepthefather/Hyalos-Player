package com.hyalos.player.ui.browser

import android.text.format.DateUtils
import android.text.format.Formatter
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
import com.hyalos.player.info.InfoSection
import com.hyalos.player.info.MediaProbe
import com.hyalos.player.info.ProbedMedia
import com.hyalos.player.info.infoSections
import com.hyalos.player.kernel.describesThePath
import com.hyalos.player.kernel.RemotePath
import com.hyalos.player.thumbnails.ThumbnailKey
import com.hyalos.player.thumbnails.ThumbnailSource
import com.hyalos.player.ui.common.EntryRow
import com.hyalos.player.ui.common.Selection
import com.hyalos.player.ui.common.UiError
import com.hyalos.player.ui.common.toUiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import uniffi.krystallos_ffi.KernelException
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

        /**
         * [rows] is [items] as the list draws them — built here, once, rather
         * than in the composable, where it would be rebuilt on every
         * recomposition. The two are produced together in the single place this
         * state is constructed, so they cannot drift apart.
         */
        data class Loaded(val items: List<BrowserItem>, val rows: List<EntryRow>) : State

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

    /**
     * How the last add-to-playlist turned out.
     *
     * Kept apart from [Report]: this is not a file operation, nothing in it fails
     * halfway, and [OperationResult]'s wording — "already exists, not
     * overwritten" — is about pasting files.
     */
    data class PlaylistNotice(val added: Int, val skipped: Int)

    /** A delete waiting on confirmation, and how much it would actually remove. */
    data class DeletePrompt(val items: List<RemoteItem>, val total: Int, val truncated: Boolean)

    /** What is selected. See [Selection] for why the state lives in one object. */
    var selection by mutableStateOf(Selection.NONE)
        private set

    /** In selection mode a tap selects; otherwise it opens. */
    val selecting: Boolean get() = selection.active

    /** Names of the selected entries, exactly as the listing reports them. */
    val selected: Set<String> get() = selection.ids

    /** The operation in flight, or `null`. */
    var busy by mutableStateOf<Operation?>(null)
        private set

    /** The last finished operation, for the report at the bottom of the screen. */
    var report by mutableStateOf<Report?>(null)
        private set

    /** The last add-to-playlist, for the confirmation at the bottom of the screen. */
    var playlistNotice by mutableStateOf<PlaylistNotice?>(null)
        private set

    var renaming by mutableStateOf<BrowserItem?>(null)
        private set

    var deletePrompt by mutableStateOf<DeletePrompt?>(null)
        private set

    /**
     * What the file-info dialog is showing, or `null` when it is closed.
     *
     * [Ready.failed] is not the same as the dialog being empty: the file's own
     * lines come from the listing and are worth showing either way, so a failed
     * read still produces sections — it just also says so, and offers to try
     * again.
     */
    sealed interface InfoState {
        data object Reading : InfoState

        data class Ready(val sections: List<InfoSection>, val failed: Boolean) : InfoState
    }

    var info by mutableStateOf<InfoState?>(null)
        private set

    /** The file being described, kept so a retry knows what to read again. */
    private var infoItem: BrowserItem? = null

    private val probe = MediaProbe(container.appContext)

    val clipboard: StateFlow<ClipboardContent?> = container.clipboard.content

    /**
     * A long press selects the item and enters selection mode.
     *
     * One mode covers both requests — acting on a single file and acting on
     * several — because they are the same intent differing only in how many
     * items are checked. Rename is offered only while exactly one is selected,
     * which is where the two cases genuinely diverge.
     */
    fun onLongPress(name: String) {
        selection = selection.select(name)
    }

    /**
     * Takes the name rather than the item: one mode covers both requests, and
     * neither needs more than which entry was touched.
     */
    fun onTap(name: String, open: () -> Unit) {
        if (selecting) toggleSelection(name) else open()
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

    /**
     * Put the selected files into this server's playlist.
     *
     * A batch action like copy and cut, so it carries no "exactly one selected"
     * condition — that belongs to rename alone.
     *
     * Only what can play goes in. A playlist exists to be played through, and
     * anything else — a folder, a text file — would be a row that leads straight
     * to an error. What is left out is counted and reported rather than passed
     * over in silence, so the numbers on screen add up to what was selected.
     */
    fun addToPlaylist() {
        val chosen = (state as? State.Loaded)?.items.orEmpty().filter { it.name in selected }
        if (chosen.isEmpty()) return
        val paths = chosen.filter { it.playable }.map { RemotePath.join(path, it.name) }
        viewModelScope.launch {
            val added = if (paths.isEmpty()) 0 else container.playlists.add(serverId, paths)
            playlistNotice = PlaylistNotice(added = added, skipped = chosen.size - added)
            clearSelection()
        }
    }

    fun dismissPlaylistNotice() {
        playlistNotice = null
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

    // ---------------------------------------------------------------------
    // File info
    // ---------------------------------------------------------------------

    fun showInfo(item: BrowserItem) {
        infoItem = item
        readInfo()
    }

    fun retryInfo() {
        if (infoItem != null) readInfo()
    }

    fun dismissInfo() {
        infoItem = null
        info = null
    }

    private fun readInfo() {
        val item = infoItem ?: return
        info = InfoState.Reading
        viewModelScope.launch {
            val media = try {
                probe(item)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The file's own lines are still worth showing, so this is not
                // an error state — `failed` carries the news instead.
                null
            }
            // The dialog may have been closed, or pointed at another file, while
            // the header was being read.
            if (infoItem !== item) return@launch
            info = InfoState.Ready(
                sections = infoSections(
                    item = item,
                    serverName = serverName,
                    path = RemotePath.join(path, item.name),
                    media = media,
                    formatSize = ::sizeText,
                    formatDate = ::dateText,
                ),
                failed = media == null,
            )
        }
    }

    /**
     * Read the file's header over a connection of its own.
     *
     * `ThumbnailSource` is the project's one implementation of `ReaderSource` —
     * a reader bound to a server, which reconnects if the session dies and
     * closes what it opened. Its name is about its first user, not about what it
     * is; the alternative here would be a second, thinner copy of the same I/O.
     *
     * A connection of its own, not the browsing one: kernel sessions run one
     * operation at a time, and a probe should never be what a directory listing
     * is waiting behind.
     */
    private suspend fun probe(item: BrowserItem): ProbedMedia {
        val source = ThumbnailSource({ container.sessions.connectDedicated(serverId) }, viewModelScope)
        return try {
            probe.probe(source, serverId, RemotePath.join(path, item.name))
        } finally {
            source.close()
        }
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
                    state = State.Loaded(sorted, sorted.map { it.toRow() })
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
     * The frame behind a thumbnail key, or `null` when there is none to show.
     *
     * Called from the row's composition, so it runs only while that row is on
     * screen and is cancelled when it scrolls away.
     */
    suspend fun loadThumbnail(key: ThumbnailKey): android.graphics.Bitmap? =
        container.thumbnails.load(key)

    /**
     * One listing entry as the list draws it.
     *
     * The key carries the size and the modification time, so a file that is
     * replaced on the server gets a fresh frame rather than the old film's. A
     * playlist knows only paths and cannot do that, so it builds a different key
     * and shares none of this cache — see `ARCHITECTURE.md`.
     */
    private fun BrowserItem.toRow(): EntryRow = EntryRow(
        id = name,
        name = name,
        detail = details(),
        icon = EntrySorting.iconFor(kind),
        thumbnail = ThumbnailKey(
            serverId = serverId,
            path = RemotePath.join(path, name),
            size = size,
            modifiedMs = modifiedMs,
        ).takeIf { kind == BrowserItem.Kind.VIDEO },
    )

    /** "1.4 GB · 2024/3/5", or whichever half is known. */
    private fun BrowserItem.details(): String? =
        listOfNotNull(size?.let(::sizeText), modifiedMs?.let(::dateText))
            .joinToString(" · ")
            .ifEmpty { null }

    /** "1.4 GB". Shared with the info dialog, which shows the same number. */
    private fun sizeText(bytes: Long): String =
        Formatter.formatShortFileSize(container.appContext, bytes)

    /** "2024/3/5". Shared with the info dialog. */
    private fun dateText(millis: Long): String = DateUtils.formatDateTime(
        container.appContext,
        millis,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_NUMERIC_DATE,
    )

    private fun load(refresh: Boolean = false) {
        job?.cancel()
        if (refresh && state is State.Loaded) refreshing = true else state = State.Loading
        job = viewModelScope.launch {
            var attempt = 0
            try {
                while (true) {
                    try {
                        entries.value = container.sessions.list(serverId, path)
                        return@launch
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Cleared so a later settings change cannot resurrect a
                        // stale listing over whatever the user is looking at.
                        entries.value = null

                        // An answer about the path is final — reconnecting will
                        // not make a missing file appear.
                        if ((e as? KernelException)?.describesThePath == true) {
                            state = State.Failed(e.toUiError())
                            return@launch
                        }

                        if (attempt < FAST_ATTEMPTS - 1) {
                            // Fast retries first, without bothering the user:
                            // coming back to a directory should not mean tapping
                            // a button, and most dropped connections are back
                            // within a second or two.
                            attempt++
                            delay(RETRY_DELAY_MS * attempt)
                        } else {
                            // Still failing — say so, but keep trying quietly.
                            // The alternative is an error screen that stays
                            // wrong after the server comes back until somebody
                            // taps it, which is exactly what a retry button is
                            // bad at.
                            state = State.Failed(e.toUiError())
                            delay(QUIET_RETRY_MS)
                        }
                    }
                }
            } finally {
                refreshing = false
            }
        }
    }

    private companion object {
        /** Shared with the player, so a playlist orders names the same way. */
        val nameOrder = EntrySorting.systemOrder

        /** Attempts before the failure is shown at all. */
        const val FAST_ATTEMPTS = 3

        /** Linear backoff for those: 1 s, then 2 s. */
        const val RETRY_DELAY_MS = 1000L

        /**
         * The cadence once the failure is on screen.
         *
         * Slow enough to be nothing on a server that is simply off, quick enough
         * that a router rebooting is over before the user has finished reading
         * the error. The loop dies with the screen, so this only runs while
         * somebody is looking at it.
         */
        const val QUIET_RETRY_MS = 10_000L
    }
}
