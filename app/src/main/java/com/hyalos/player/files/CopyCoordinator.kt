package com.hyalos.player.files

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** How far along a running copy is, and what to call it. */
data class CopyProgress(val label: String, val copied: ULong, val total: ULong) {

    /**
     * How much of the whole, or null while that is not yet known.
     *
     * Null rather than zero: a copy spends its first moments tallying what it is
     * about to move, and a bar sitting at nothing reads as "stuck" where a
     * spinner reads as "working". The notification shows one or the other.
     */
    val fraction: Float?
        get() = if (total > 0uL) (copied.toFloat() / total.toFloat()).coerceIn(0f, 1f) else null
}

/**
 * The copy that is running, if one is.
 *
 * Process-wide rather than per-screen, because the work outlives whatever
 * started it: a film copied between two NASes takes minutes, and in that time
 * the user may leave the folder, open the player, or turn the screen off —
 * none of which should end it, and the last of which puts this process in line
 * to be reclaimed. So the operation lives here, beside the clipboard, and
 * [CopyService] exists to keep the process alive while it runs.
 *
 * **One at a time.** Two copies interleaving on the same sessions would report
 * progress that belongs to neither, and the notification can only describe one
 * thing. A second request is refused rather than queued: the caller can say so,
 * and the user can try again in a moment.
 */
class CopyCoordinator(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    private val _progress = MutableStateFlow<CopyProgress?>(null)
    val progress: StateFlow<CopyProgress?> = _progress.asStateFlow()

    private var job: Job? = null

    val busy: Boolean get() = job?.isActive == true

    /**
     * Run [work] with the process held up for as long as it takes.
     *
     * [work] is handed a reporter rather than a total: only the operation knows
     * how much it is about to move, and only it knows when that becomes known.
     * Returns false when something is already running.
     */
    fun start(
        label: String,
        work: suspend (report: (copied: ULong, total: ULong) -> Unit) -> OperationResult,
    ): Boolean {
        if (busy) return false

        _progress.value = CopyProgress(label, 0uL, 0uL)
        CopyService.begin(context)

        job = scope.launch {
            try {
                work { copied, total -> _progress.value = CopyProgress(label, copied, total) }
            } finally {
                // Whatever happened. A notification left behind after the copy
                // stopped is worse than no notification: it claims work that is
                // not happening, and cannot be dismissed.
                _progress.value = null
                CopyService.end(context)
            }
        }
        return true
    }

    /** Called from the notification's own action, so it works with the app in the background. */
    fun cancel() {
        job?.cancel()
    }
}
