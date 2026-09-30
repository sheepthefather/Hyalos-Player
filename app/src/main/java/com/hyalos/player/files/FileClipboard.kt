package com.hyalos.player.files

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the user copied or cut, waiting to be pasted.
 *
 * Process-wide rather than per-screen, because the whole point is to carry
 * something from one directory to another — and the paste may well happen on a
 * different server, which is why each item remembers where it came from.
 *
 * **Pasting empties it, either mode.** Once something has been pasted the
 * clipboard is done with it, and the top bar's paste button goes with it.
 *
 * A copy used to be kept, on the reasoning that somebody who copies a film and
 * pastes it into three folders means to paste it three times. That reasoning
 * is sound but the result was not: the button then never went away, and there
 * was no way at all to dismiss it, so the clipboard was a one-way door. A cut
 * had the sharper reason: after a partial move, some of what it holds has
 * already left the source, and a second paste would be pasting items that are
 * no longer there.
 */
class FileClipboard {
    private val state = MutableStateFlow<ClipboardContent?>(null)

    val content: StateFlow<ClipboardContent?> = state.asStateFlow()

    fun copy(items: List<RemoteItem>) {
        if (items.isEmpty()) return
        state.value = ClipboardContent(ClipboardMode.COPY, items)
    }

    fun cut(items: List<RemoteItem>) {
        if (items.isEmpty()) return
        state.value = ClipboardContent(ClipboardMode.CUT, items)
    }

    /**
     * Called once a paste has reported, whatever it managed to do.
     *
     * Deliberately not conditional on success. What a failed paste leaves
     * behind is worth re-reading to make the decision again — that is what the
     * report and the leftover notice are for — and a clipboard kept for a retry
     * would hold items a partly-done cut has already moved away.
     */
    fun consume() {
        state.value = null
    }

    fun clear() {
        state.value = null
    }
}
