package com.hyalos.player.data

import android.content.Context
import android.os.Environment

/**
 * The device's own storage, as a source the rest of the app already knows how to
 * talk to.
 *
 * There is no local file code anywhere below this line. The kernel's `file:`
 * backend implements the same operations as its SMB one — list, stat, read,
 * rename, remove, copy, mkdir — so a local directory is opened as a *session*
 * and everything above it is the code that was already here: the browser, the
 * file operations, the player's data source, the thumbnail pipeline.
 *
 * That is why this is an id and a path rather than a subsystem.
 */
object LocalSource {

    /**
     * Deliberately not uuid-shaped, because every real server's id is: an id
     * this app cannot mint for a server is one that cannot collide with one.
     */
    const val ID = "local"

    /** What "本地文件" means to the person holding the phone. */
    val root: String get() = Environment.getExternalStorageDirectory().absolutePath

    /**
     * The endpoint the kernel's local backend wants.
     *
     * Built here rather than by joining strings at the call site: the kernel
     * exposes `uri_for` precisely because getting a `file://` URL right by hand
     * is easy to get wrong. On Android the path is always absolute and
     * POSIX-shaped, so it is the simple case — `file:///storage/emulated/0`.
     */
    val uri: String get() = "file://$root"

    /**
     * Whether the app may read the whole of shared storage.
     *
     * Checked rather than requested: `MANAGE_EXTERNAL_STORAGE` has no dialog. The
     * user turns it on themselves on a settings screen, which is why the local
     * tab has a page explaining what it is for instead of a button that pops a
     * prompt.
     */
    fun granted(): Boolean = Environment.isExternalStorageManager()
}
