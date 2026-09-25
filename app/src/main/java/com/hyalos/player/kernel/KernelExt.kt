package com.hyalos.player.kernel

import uniffi.krystallos_ffi.KernelException
import uniffi.krystallos_ffi.OpenFlags
import uniffi.krystallos_ffi.RemoteFile
import uniffi.krystallos_ffi.Session

/**
 * Open an existing file for reading. The Rust side has a constructor for this
 * but UniFFI does not export associated functions on records, so it is
 * spelled out once here.
 */
val READ_ONLY = OpenFlags(read = true, write = false, create = false, createNew = false, truncate = false)

/**
 * Disconnect and release the Kotlin handle, whatever state the session is in.
 *
 * Two different operations: [Session.disconnect] tears the connection down on
 * the Rust side, [Session.close] frees the Kotlin wrapper's reference to it.
 * A failed disconnect is swallowed — a session being shut down has nothing left
 * to report to — but the handle is released regardless.
 */
suspend fun Session.shutdown() {
    try {
        disconnect()
    } catch (_: KernelException) {
    } finally {
        close()
    }
}

/** [Session.shutdown] for a file: release the server-side handle, then the Kotlin one. */
suspend fun RemoteFile.shutdown() {
    try {
        release()
    } catch (_: KernelException) {
    } finally {
        close()
    }
}

/**
 * Whether this failure is an **answer about the path** rather than a symptom of
 * the connection.
 *
 * The distinction decides whether an operation is worth repeating on a fresh
 * session. "That file does not exist" will be just as true after reconnecting,
 * so retrying it only wastes a round trip; anything else might be a connection
 * that has died, and for a read the retry costs nothing but the retry.
 *
 * # Why the app has to make this call
 *
 * The kernel is careful about `ConnectionLost` — it means the session is dead —
 * but it cannot always tell. `smb2_opendir` and friends report failure by
 * returning a **null pointer**, with no return code to inspect, so a socket that
 * the server has closed arrives as a generic `Backend` error carrying
 * libsmb2's text ("`smb2_service: POLLHUP, socket error`"). The kernel cannot
 * recognise that as a lost connection without matching prose, and there is no
 * non-textual signal to use instead: `smb2_get_fd` would answer, but libsmb2
 * leaves the descriptor open when the peer hangs up.
 *
 * So the app takes the safe reading: treat everything that is not a statement
 * about the path as possibly-the-connection. That covers this case and its
 * relatives without depending on the wording of a C library's error strings.
 */
val KernelException.describesThePath: Boolean
    get() = when (this) {
        is KernelException.NotFound,
        is KernelException.PermissionDenied,
        is KernelException.AlreadyExists,
        is KernelException.NotADirectory,
        is KernelException.IsADirectory,
        is KernelException.DirectoryNotEmpty,
        is KernelException.InvalidPath,
        is KernelException.Unsupported,
        -> true
        // `ConnectionLost`, `Auth` and `Backend` are all things a fresh session
        // might answer differently.
        else -> false
    }
