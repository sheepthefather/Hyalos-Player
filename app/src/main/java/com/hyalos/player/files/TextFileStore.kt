package com.hyalos.player.files

import com.hyalos.player.kernel.READ_ONLY
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import uniffi.krystallos_ffi.KernelException
import uniffi.krystallos_ffi.OpenFlags
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Reading a file for the editor, and putting it back afterwards.
 *
 * Both halves exist because the kernel's file API is a byte-range API: there is
 * no "read this whole file" and no "replace this file's contents". Reading is a
 * loop with a ceiling; writing is a loop into a temporary file followed by
 * renames.
 *
 * # Why saving never writes in place
 *
 * A save that fails half way through an in-place write leaves the file shorter
 * than either the old or the new version — the one outcome the user cannot
 * recover from, because neither copy exists any more. So the new bytes are
 * written to `<path>.hyalos-edit` in full, and only then does the real file
 * change.
 *
 * # The order of the renames
 *
 * The original is **not** deleted to make room. It is renamed aside first, which
 * works identically on both backends — the SMB one refuses to rename onto an
 * existing name, while the local one replaces — and means the file's contents
 * are never the thing being removed. If the new file cannot take the name, the
 * original is renamed back. The worst case leaves both versions on the server
 * under names that say what they are, rather than one version gone.
 */

/** Suffix of the file being written while a save is in flight. */
internal const val EDIT_TEMP_SUFFIX = ".hyalos-edit"

/** Suffix the original wears while it is being replaced. */
internal const val EDIT_BACKUP_SUFFIX = ".hyalos-old"

/** One read or write per chunk keeps the FFI call count sane for a 1 MiB file. */
private const val CHUNK = 1 shl 20

/**
 * New and write-only. Deliberately not combined with `truncate`: the kernel
 * applies both, producing `O_EXCL | O_TRUNC`, and `createNew` alone is the
 * documented "refuse rather than replace".
 */
private val WRITE_NEW = OpenFlags(
    read = false,
    write = true,
    create = true,
    createNew = true,
    truncate = false,
)

/** What came of reading a file for editing. */
internal sealed interface ReadResult {
    data class Bytes(val value: ByteArray) : ReadResult

    /**
     * Bigger than the editor will hold.
     *
     * Decided from `stat` **before any byte is read** where the server gives a
     * size, and by the read loop otherwise. Reading first and judging after
     * would download a two-gigabyte log into the heap to then refuse it.
     */
    data object TooLarge : ReadResult
}

/**
 * The whole of [path], up to [limit] bytes.
 *
 * The stat is an early exit, not the authority: a file can grow between the
 * stat and the read, so the loop stops at `limit + 1` as well and the two
 * together are what makes the ceiling hold.
 */
internal suspend fun readFileBytes(
    session: FileSession,
    path: String,
    limit: Int,
): ReadResult {
    val declared = runCatching { session.stat(path).len.toLong() }.getOrNull()
    if (declared != null && declared > limit) return ReadResult.TooLarge

    val handle = session.open(path, READ_ONLY)
    try {
        val out = ByteArrayOutputStream(minOf(declared ?: 0L, limit.toLong()).toInt().coerceAtLeast(0))
        var offset = 0uL
        while (true) {
            // One byte over the ceiling, so a file that grew since the stat is
            // still caught rather than truncated into something that looks fine.
            val room = limit + 1 - out.size()
            if (room <= 0) return ReadResult.TooLarge
            val chunk = handle.read(offset, minOf(CHUNK, room))
            if (chunk.isEmpty()) break
            out.write(chunk)
            offset += chunk.size.toULong()
        }
        if (out.size() > limit) return ReadResult.TooLarge
        return ReadResult.Bytes(out.toByteArray())
    } finally {
        withContext(NonCancellable) { runCatching { handle.release() } }
    }
}

/**
 * Which step of a save failed, in terms of what survived it.
 *
 * The two are the whole point of the message: [WRITE] means the file on the
 * server is exactly as it was, [SWAP] means the new content exists but under
 * another name, and the user has to be told which name.
 */
enum class SaveStage {
    /** Nothing changed. The original is untouched and the new content is gone. */
    WRITE,

    /** The new content exists, but the name the user knows belongs to neither version. */
    SWAP,
}

/**
 * A save that failed, described by what is on the server now.
 *
 * [temporary] holds the new content when [stage] is [SaveStage.SWAP];
 * [backup] holds the original, or is `null` when the original is where it was.
 */
internal class TextSaveException(
    val stage: SaveStage,
    val temporary: String,
    val backup: String?,
    cause: Throwable? = null,
) : Exception(cause)

/**
 * Replace [path] with [bytes], leaving a usable file behind whatever happens.
 *
 * Three renames' worth of care for a file that is at most a megabyte: the whole
 * point is that there is no moment at which *neither* version has a name.
 */
internal suspend fun writeTextFile(session: FileSession, path: String, bytes: ByteArray) {
    val temporary = path + EDIT_TEMP_SUFFIX
    val backup = path + EDIT_BACKUP_SUFFIX

    writeTemporary(session, temporary, bytes)

    // A `.hyalos-old` from a save that died before it could clean up is ours, so
    // it is cleared rather than allowed to block this one.
    runCatching { session.removeFile(backup) }
    try {
        session.rename(path, backup)
    } catch (e: CancellationException) {
        // Reached in the middle of replacing the user's file: the temporary is
        // waste, the original has not moved, and this is worth doing even though
        // the caller is going away.
        withContext(NonCancellable) { runCatching { session.removeFile(temporary) } }
        throw e
    } catch (e: Exception) {
        withContext(NonCancellable) { runCatching { session.removeFile(temporary) } }
        throw TextSaveException(SaveStage.WRITE, temporary, backup = null, cause = e)
    }

    try {
        session.rename(temporary, path)
    } catch (e: CancellationException) {
        restore(session, backup, path, temporary)
        throw e
    } catch (e: Exception) {
        // Put the original back. A save that failed and changed nothing is a
        // nuisance; a save that failed and took the file with it is a loss.
        val restored = restore(session, backup, path, temporary)
        throw if (restored) {
            TextSaveException(SaveStage.WRITE, temporary, backup = null, e)
        } else {
            TextSaveException(SaveStage.SWAP, temporary, backup, e)
        }
    }

    // The new content has the name. The backup is waste now, and failing to
    // delete waste is not a failed save.
    runCatching { session.removeFile(backup) }
}

/** Put the original back where it was, and drop the temporary if that worked. */
private suspend fun restore(
    session: FileSession,
    backup: String,
    path: String,
    temporary: String,
): Boolean =
    withContext(NonCancellable) {
        val restored = try {
            session.rename(backup, path)
            true
        } catch (_: Exception) {
            false
        }
        if (restored) runCatching { session.removeFile(temporary) }
        restored
    }

/**
 * Write [bytes] to [path] in full, then release.
 *
 * A half-written temporary is removed on the way out: it holds no version of
 * anything the user knows, and leaving it would make the next save refuse to
 * start. The flush is what turns "the kernel accepted the bytes" into "the
 * server did".
 */
private suspend fun writeTemporary(session: FileSession, path: String, bytes: ByteArray) {
    val handle = try {
        openNew(session, path)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw TextSaveException(SaveStage.WRITE, path, backup = null, cause = e)
    }

    var whole = false
    try {
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(bytes.size, offset + CHUNK)
            val n = handle.write(offset.toULong(), bytes.copyOfRange(offset, end))
            if (n <= 0) throw IOException("short write at $offset on $path")
            offset += n
        }
        handle.flush()
        whole = true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw TextSaveException(SaveStage.WRITE, path, backup = null, cause = e)
    } finally {
        // A flush that failed is only known about here, and releasing first
        // would hide it behind a close.
        runCatching { withContext(NonCancellable) { handle.release() } }
        if (!whole) withContext(NonCancellable) { runCatching { session.removeFile(path) } }
    }
}

/** Open the temporary new, clearing a leftover that has the same name. */
private suspend fun openNew(session: FileSession, path: String): FileHandle =
    try {
        session.open(path, WRITE_NEW)
    } catch (_: KernelException.AlreadyExists) {
        session.removeFile(path)
        session.open(path, WRITE_NEW)
    }
