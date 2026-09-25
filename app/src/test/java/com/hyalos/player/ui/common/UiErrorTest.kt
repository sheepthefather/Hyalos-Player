package com.hyalos.player.ui.common

import com.hyalos.player.R
import com.hyalos.player.kernel.ServerNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.krystallos_ffi.KernelException

class UiErrorTest {
    @Test
    fun `each failure the user can act on gets its own action`() {
        assertEquals(UiError.Action.EDIT_SERVER, KernelException.Auth("bad").toUiError().action)
        assertEquals(UiError.Action.RETRY, KernelException.ConnectionLost("gone").toUiError().action)
        assertEquals(UiError.Action.NONE, KernelException.NotFound("/a").toUiError().action)
        assertEquals(UiError.Action.EDIT_SERVER, KernelException.PermissionDenied("/a").toUiError().action)
        assertEquals(UiError.Action.NONE, ServerNotFoundException("x").toUiError().action)
    }

    @Test
    fun `the path travels with path errors`() {
        val e = KernelException.NotFound("/movies/a.mkv").toUiError()
        assertEquals(R.string.error_not_found, e.message)
        assertEquals("/movies/a.mkv", e.arg)
    }

    @Test
    fun `anything else keeps the kernel's own wording`() {
        val e = KernelException.Backend("STATUS_SHARING_VIOLATION").toUiError()
        assertEquals(R.string.error_generic, e.message)
        assertEquals(true, e.arg?.contains("STATUS_SHARING_VIOLATION"))
    }
}
