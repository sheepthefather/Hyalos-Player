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
