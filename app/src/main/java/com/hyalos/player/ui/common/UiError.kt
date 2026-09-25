package com.hyalos.player.ui.common

import androidx.annotation.StringRes
import com.hyalos.player.R
import com.hyalos.player.kernel.ServerNotFoundException
import uniffi.krystallos_ffi.KernelException

/**
 * A failure as the UI presents it: what to say, and what the user can do.
 *
 * The kernel already sorts failures into categories a UI can act on — that is
 * why `ConnectionLost` and `NotFound` are different types — so this is mostly a
 * lookup table from those categories to a sentence and a button.
 */
data class UiError(
    @StringRes val message: Int,
    val arg: String? = null,
    val action: Action,
) {
    enum class Action {
        /** Try the same thing again — the problem may be transient. */
        RETRY,

        /** The server's settings are what is wrong; offer to edit them. */
        EDIT_SERVER,

        /** Nothing here will help; the user can only go elsewhere. */
        NONE,
    }
}

fun Throwable.toUiError(): UiError = when (this) {
    is KernelException.Auth -> UiError(R.string.error_auth, action = UiError.Action.EDIT_SERVER)
    is KernelException.ConnectionLost -> UiError(R.string.error_connection, action = UiError.Action.RETRY)
    is KernelException.NotFound -> UiError(R.string.error_not_found, path, UiError.Action.NONE)
    // A different account may have access, so editing is the useful action.
    is KernelException.PermissionDenied ->
        UiError(R.string.error_permission, path, UiError.Action.EDIT_SERVER)
    is KernelException.NotADirectory ->
        UiError(R.string.error_not_a_directory, path, UiError.Action.NONE)
    is ServerNotFoundException -> UiError(R.string.error_server_gone, action = UiError.Action.NONE)
    // Everything else keeps the kernel's own wording. Losing it would make
    // server-specific quirks undiagnosable.
    else -> UiError(R.string.error_generic, message ?: javaClass.simpleName, UiError.Action.RETRY)
}
