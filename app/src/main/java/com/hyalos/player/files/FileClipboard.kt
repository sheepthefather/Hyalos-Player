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
 * **A copy survives pasting; a cut does not.** Somebody who copies a film and
 * pastes it into three folders means to paste it three times; somebody who cuts
 * it means to move it once, and leaving it on the clipboard afterwards would
 * invite a second paste that could only be a mistake.
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

    /** Called after a cut has been pasted; a copy is left alone. */
    fun consumeIfCut() {
        if (state.value?.mode == ClipboardMode.CUT) state.value = null
    }

    fun clear() {
        state.value = null
    }
}
