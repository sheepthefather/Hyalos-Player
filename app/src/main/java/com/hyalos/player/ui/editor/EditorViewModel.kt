package com.hyalos.player.ui.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hyalos.player.AppContainer
import com.hyalos.player.ChangedPath
import com.hyalos.player.data.LocalSource
import com.hyalos.player.files.DecodeResult
import com.hyalos.player.files.EncodeResult
import com.hyalos.player.files.FileSession
import com.hyalos.player.files.KernelFileSession
import com.hyalos.player.files.MAX_EDIT_BYTES
import com.hyalos.player.files.ReadResult
import com.hyalos.player.files.SaveStage
import com.hyalos.player.files.TextFile
import com.hyalos.player.files.TextSaveException
import com.hyalos.player.files.decodeText
import com.hyalos.player.files.encodeText
import com.hyalos.player.files.openCacheTarget
import com.hyalos.player.files.readFileBytes
import com.hyalos.player.files.writeTextFile
import com.hyalos.player.kernel.READ_ONLY
import com.hyalos.player.kernel.RemotePath
import com.hyalos.player.kernel.shutdown
import com.hyalos.player.ui.common.UiError
import com.hyalos.player.ui.common.toUiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * One text file, open for editing.
 *
 * Owned by its back-stack entry, so coming back to it — from the player, from
 * the settings page — finds the same text and the same unsaved changes.
 *
 * # The text is a separate thing from the file
 *
 * [text] is what the field shows; [savedText] is what the server last confirmed
 * it holds. Everything else is derived: "dirty" is those two disagreeing, so a
 * save that is still in flight while the user types cannot mark the newer
 * keystrokes as saved — the snapshot that was written is what [savedText]
 * becomes.
 *
 * # One connection per operation
 *
 * Reading, saving and downloading for another app each open their own dedicated
 * session and close it. The browsing session is shared and runs one operation at
 * a time; an editor that held it open for the length of a visit would freeze the
 * directory listing the user will go back to.
 */
class EditorViewModel(
    private val container: AppContainer,
    val serverId: String,
    val path: String,
    val readOnly: Boolean,
) : ViewModel() {

    /** Why the editor will not show a file. */
    enum class Refusal {
        /** NUL bytes where text should be. */
        BINARY,

        /** Over [MAX_EDIT_BYTES]. */
        TOO_LARGE,

        /** Neither UTF-8 nor GBK. */
        UNSUPPORTED,
    }

    sealed interface State {
        data object Loading : State

        /**
         * Refused before editing, with the file left exactly as it was.
         *
         * A state rather than an error: nothing went wrong, the editor simply is
         * not the right tool, and "用其他应用打开" is the reasonable next step.
         */
        data class Unreadable(val reason: Refusal) : State

        /** The read itself failed — gone, unreachable, not permitted. */
        data class Failed(val error: UiError) : State

        data class Ready(val file: TextFile) : State
    }

    /** What a failed save left behind, in the terms the dialog has to explain. */
    sealed interface SaveFailure {
        /** The file's own encoding cannot hold one of the characters typed. */
        data object Unmappable : SaveFailure

        /**
         * [stage] says whether the original is still in place ([SaveStage.WRITE])
         * or wearing [backup]'s name ([SaveStage.SWAP]).
         */
        data class Staged(
            val stage: SaveStage,
            val temporary: String,
            val backup: String?,
            val reason: String?,
        ) : SaveFailure
    }

    /** A file on its way to another app. */
    data class Preparing(val name: String, val done: Long, val total: Long)

    enum class Notice { SAVED, OPEN_FAILED, NOTHING_TO_OPEN }

    var state by mutableStateOf<State>(State.Loading)
        private set

    /** The editor's content, normalised to `\n` by the decoder. */
    var text by mutableStateOf("")
        private set

    /** What the server last confirmed. See the class note on why this is separate. */
    private var savedText by mutableStateOf("")

    val dirty: Boolean get() = text != savedText

    var saving by mutableStateOf(false)
        private set

    var failure by mutableStateOf<SaveFailure?>(null)
        private set

    var notice by mutableStateOf<Notice?>(null)
        private set

    var preparing by mutableStateOf<Preparing?>(null)
        private set

    var readyToOpen by mutableStateOf<File?>(null)
        private set

    private var openJob: Job? = null

    /** What the server said the file weighs, for the download warning. */
    var sizeBytes by mutableStateOf<Long?>(null)
        private set

    /** The file's name, as the title and the cache entry use it. */
    val name: String get() = RemotePath.name(path)

    /**
     * Whether long lines wrap. A stored preference, shared with every other
     * file this editor opens — see `AppSettings.editorWrap`.
     */
    val wrap: StateFlow<Boolean> = container.settings.settings
        .map { it.editorWrap }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun toggleWrap() {
        viewModelScope.launch { container.settings.setEditorWrap(!wrap.value) }
    }

    /**
     * Whether opening this file in another app means downloading it first.
     *
     * Only on a share. A local file is already somewhere every app can reach.
     */
    val downloadsToOpen: Boolean get() = serverId != LocalSource.ID

    /** Whether that download is big enough to be worth asking about. */
    val largeDownload: Boolean
        get() = downloadsToOpen && state is State.Unreadable && (sizeBytes ?: 0L) >= LARGE_OPEN_BYTES

    /** The editor owns the text; the field writes through here. */
    fun edit(value: String) {
        if (readOnly) return
        text = value
    }

    init {
        load()
    }

    fun retry() = load()

    private fun load() {
        state = State.Loading
        failure = null
        viewModelScope.launch {
            try {
                val result = withSession { session ->
                    sizeBytes = runCatching { session.stat(path).len.toLong() }.getOrNull()
                    readFileBytes(session, path, MAX_EDIT_BYTES)
                }
                state = when (result) {
                    ReadResult.TooLarge -> State.Unreadable(Refusal.TOO_LARGE)
                    is ReadResult.Bytes -> when (val decoded = decodeText(result.value)) {
                        is DecodeResult.Text -> {
                            text = decoded.file.text
                            savedText = decoded.file.text
                            State.Ready(decoded.file)
                        }
                        DecodeResult.Binary -> State.Unreadable(Refusal.BINARY)
                        DecodeResult.Unsupported -> State.Unreadable(Refusal.UNSUPPORTED)
                        DecodeResult.TooLarge -> State.Unreadable(Refusal.TOO_LARGE)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = State.Failed(e.toUiError())
            }
        }
    }

    /**
     * Write the text back to where it came from.
     *
     * The snapshot is taken before the write starts: the user may keep typing
     * while the bytes are on their way, and what the server now holds is the
     * snapshot, not whatever the field says a moment later.
     */
    fun save() {
        val ready = state as? State.Ready ?: return
        if (readOnly || saving) return
        val snapshot = text
        saving = true
        viewModelScope.launch {
            try {
                val encoded = encodeText(ready.file, snapshot)
                if (encoded is EncodeResult.Bytes) {
                    withSession { session -> writeTextFile(session, path, encoded.value) }
                    savedText = snapshot
                    notice = Notice.SAVED
                    // The listing behind this screen still shows the old size and
                    // date. Nothing here can re-read it, so the news is published
                    // and the browser picks it up when it is shown again.
                    container.changes.tryEmit(ChangedPath(serverId, path))
                } else {
                    failure = SaveFailure.Unmappable
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: TextSaveException) {
                failure = SaveFailure.Staged(
                    stage = e.stage,
                    temporary = RemotePath.name(e.temporary),
                    backup = e.backup?.let(RemotePath::name),
                    reason = e.cause?.message,
                )
            } catch (e: Exception) {
                failure = SaveFailure.Staged(
                    stage = SaveStage.WRITE,
                    temporary = "",
                    backup = null,
                    reason = e.message,
                )
            } finally {
                saving = false
            }
        }
    }

    fun dismissFailure() {
        failure = null
    }

    fun dismissNotice() {
        notice = null
    }

    /**
     * Hand the file to another app.
     *
     * Two different files, depending on what the editor has: when the text is on
     * screen it is the **buffer** that is handed over — the user is looking at
     * edits that would otherwise be lost to a viewer reading the old file — and
     * when the editor refused the file it is the **original** that goes.
     */
    fun openExternally() {
        if (openJob != null) return
        var job: Job? = null
        job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            var target: File? = null
            var copied = false
            var completed = false
            try {
                val file = when (val current = state) {
                    is State.Ready -> {
                        val bytes = when (val encoded = encodeText(current.file, text)) {
                            is EncodeResult.Bytes -> encoded.value
                            EncodeResult.Unmappable -> {
                                failure = SaveFailure.Unmappable
                                return@launch
                            }
                        }
                        openCacheTarget(container.appContext, name).also {
                            target = it
                            copied = true
                            withContext(Dispatchers.IO) {
                                it.parentFile?.mkdirs()
                                it.writeBytes(bytes)
                            }
                        }
                    }
                    is State.Unreadable -> if (downloadsToOpen) {
                        openCacheTarget(container.appContext, name).also {
                            target = it
                            copied = true
                            download(it)
                        }
                    } else {
                        // Already readable by anyone: no copy is needed at all.
                        File(LocalSource.root, path.removePrefix("/"))
                    }
                    State.Loading, is State.Failed -> {
                        notice = Notice.NOTHING_TO_OPEN
                        return@launch
                    }
                }
                if (!file.isFile) {
                    notice = Notice.OPEN_FAILED
                    return@launch
                }
                completed = true
                readyToOpen = file
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                notice = Notice.OPEN_FAILED
            } finally {
                // Only our own copy is ever removed; a local file is the user's.
                if (!completed && copied) {
                    target?.delete()
                    target?.parentFile?.delete() // the UUID directory, now empty
                }
                preparing = null
                if (openJob === job) openJob = null
            }
        }
        openJob = job
        job.start()
    }

    /** Stops a download in progress; the partly written file goes with it. */
    fun cancelOpen() {
        val job = openJob
        openJob = null
        preparing = null
        job?.cancel()
    }

    /** The screen has handed [readyToOpen] on; forget it. */
    fun consumeReadyToOpen() {
        readyToOpen = null
    }

    /**
     * Copies the original into [target], reporting progress.
     *
     * Chunked rather than read whole: this path is reached precisely because the
     * file was too large for the editor, so it must not be too large for this.
     */
    private suspend fun download(target: File) {
        withSession { session ->
            val handle = session.open(path, READ_ONLY)
            try {
                val total = sizeBytes ?: 0L
                preparing = Preparing(name, 0, total)
                withContext(Dispatchers.IO) {
                    target.parentFile?.mkdirs()
                    target.outputStream().buffered().use { out ->
                        var offset = 0uL
                        while (true) {
                            coroutineContext.ensureActive()
                            val chunk = handle.read(offset, DOWNLOAD_CHUNK)
                            if (chunk.isEmpty()) break
                            out.write(chunk)
                            offset += chunk.size.toULong()
                            preparing = Preparing(name, offset.toLong(), total)
                        }
                    }
                }
            } finally {
                handle.release()
            }
        }
    }

    /** Run [block] on a connection of its own, closed whatever happens. */
    private suspend fun <T> withSession(block: suspend (FileSession) -> T): T {
        val session = container.sessions.connectDedicated(serverId)
        try {
            return block(KernelFileSession(session))
        } finally {
            withContext(NonCancellable) { runCatching { session.shutdown() } }
        }
    }

    private companion object {
        /** One SMB read per chunk. */
        const val DOWNLOAD_CHUNK = 1 shl 20
    }
}

/**
 * Above this, downloading a file just to hand it on is worth asking about first.
 *
 * The same threshold the browser uses, so the same file raises the same
 * question whichever screen it is opened from.
 */
internal const val LARGE_OPEN_BYTES = 1L shl 30
