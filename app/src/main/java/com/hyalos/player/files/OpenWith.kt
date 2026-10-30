package com.hyalos.player.files

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.hyalos.player.R
import java.io.File
import java.util.UUID

/**
 * Handing a file to another app, the way a file manager does.
 *
 * Two sources, two answers. A file on the device is already where the other app
 * can reach it, so it is served in place. A file on a share is **downloaded
 * first**: no app on the phone can read an SMB path, and the kernel's session is
 * not something a `content://` URI can carry. The download lives under
 * `cacheDir/open/` and is cleaned up by [purgeOpenCache].
 *
 * The file is handed over as a `content://` URI from our own [FileProvider]:
 * `file://` has been refused in intents since Android 7.
 */

/** Where a downloaded file waits for the app that will open it. */
internal const val OPEN_DIR = "open"

/** How much the downloaded files may occupy before the oldest are dropped. */
internal const val OPEN_CACHE_BUDGET_BYTES = 512L * 1024 * 1024

/** How long a downloaded file is kept. An hour is long enough for any viewer. */
internal const val OPEN_CACHE_MAX_AGE_MS = 24L * 60 * 60 * 1000

/**
 * MIME types worth naming ourselves.
 *
 * `MimeTypeMap` knows the common ones but is incomplete exactly where a file
 * browser meets the most files: nothing for 7z or rar on some builds, nothing
 * for epub or several archive extensions. A wrong or missing type means no app
 * offers to open it, which is the failure this table exists to avoid.
 */
private val MIME_TYPES = mapOf(
    "txt" to "text/plain",
    "log" to "text/plain",
    "md" to "text/markdown",
    "csv" to "text/csv",
    "json" to "application/json",
    "xml" to "text/xml",
    "html" to "text/html",
    "htm" to "text/html",
    "pdf" to "application/pdf",
    "epub" to "application/epub+zip",
    "mobi" to "application/x-mobipocket-ebook",
    "zip" to "application/zip",
    "rar" to "application/vnd.rar",
    "7z" to "application/x-7z-compressed",
    "tar" to "application/x-tar",
    "gz" to "application/gzip",
    "bz2" to "application/x-bzip2",
    "xz" to "application/x-xz",
    "iso" to "application/x-iso9660-image",
    "apk" to "application/vnd.android.package-archive",
    "jpg" to "image/jpeg",
    "jpeg" to "image/jpeg",
    "png" to "image/png",
    "gif" to "image/gif",
    "webp" to "image/webp",
    "bmp" to "image/bmp",
    "heic" to "image/heic",
    "heif" to "image/heif",
    "avif" to "image/avif",
    "svg" to "image/svg+xml",
    "tif" to "image/tiff",
    "tiff" to "image/tiff",
    "doc" to "application/msword",
    "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "xls" to "application/vnd.ms-excel",
    "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "ppt" to "application/vnd.ms-powerpoint",
    "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "srt" to "application/x-subrip",
    "ass" to "text/x-ssa",
    "vtt" to "text/vtt",
)

/**
 * What to tell the chooser this file is.
 *
 * The table first, the platform second, `application/octet-stream` last: the
 * last one opens for almost nothing, but it is also the honest answer for a
 * type nobody has heard of — and some archive managers do accept it.
 */
internal fun mimeTypeFor(name: String): String {
    val extension = name.substringAfterLast('.', "").lowercase()
    MIME_TYPES[extension]?.let { return it }
    // Best-effort and allowed to fail: the platform table is a convenience, and
    // the fallback below is already a complete answer. (It is also the only
    // path an answer can take in a JVM test, where the framework is a stub.)
    val platform = runCatching {
        MimeTypeMap.getSingleton()?.getMimeTypeFromExtension(extension)
    }.getOrNull()
    return platform ?: "application/octet-stream"
}

/**
 * A file name that is safe to write on our own disk.
 *
 * The name comes from a remote listing, so it is data from outside: separators
 * are flattened so it cannot escape the directory it is given, and the entries
 * that mean "a directory" in POSIX are replaced rather than honoured.
 */
internal fun safeFileName(name: String): String {
    val cleaned = name
        .replace('/', '_')
        .replace('\\', '_')
        .trim()
    return if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") "file" else cleaned
}

internal fun openCacheDir(context: Context): File = File(context.cacheDir, OPEN_DIR)

/**
 * Where one file is downloaded to.
 *
 * A directory per open, named by a UUID: two files with the same name from two
 * folders — or two servers — must not overwrite each other, and the receiving
 * app is shown the file's own name, which is the last path segment.
 */
internal fun openCacheTarget(context: Context, name: String): File =
    File(File(openCacheDir(context), UUID.randomUUID().toString()), safeFileName(name))

/**
 * Hands [file] to the chooser. False means nothing on the device can open it.
 *
 * The targets are counted before the chooser is shown **because a chooser
 * always resolves** — it is the system's own activity. Starting one that has
 * nothing to list would look like success, and the user would be told nothing.
 */
internal fun openWith(context: Context, file: File): Boolean {
    val uri = try {
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    } catch (_: IllegalArgumentException) {
        // Outside every root in file_paths.xml. Ours to get wrong, so the
        // caller gets the same answer as "no app" rather than a crash.
        return false
    }
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeTypeFor(file.name))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // Some viewers read the URI from the clip rather than the data field.
        clipData = ClipData.newRawUri(file.name, uri)
    }
    val handlers = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
    if (handlers.isEmpty()) return false
    return try {
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.open_with)))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

/**
 * Drops downloaded files that are too old, then the oldest ones over budget.
 *
 * Called when the app starts. Nothing else owns these files: the viewer that
 * opened one has its own handle, and once it is done the copy is only waste.
 *
 * Returns how many entries were removed, which is what a test can assert.
 */
internal fun purgeOpenCache(
    root: File,
    nowMs: Long,
    maxAgeMs: Long = OPEN_CACHE_MAX_AGE_MS,
    budgetBytes: Long = OPEN_CACHE_BUDGET_BYTES,
): Int {
    val entries = root.listFiles() ?: return 0
    var deleted = 0
    var total = 0L
    val kept = ArrayList<Pair<File, Long>>(entries.size)
    for (entry in entries) {
        val size = entry.sizeOfTree()
        if (nowMs - entry.lastModified() > maxAgeMs) {
            if (entry.deleteRecursively()) deleted++
            continue
        }
        kept += entry to size
        total += size
    }
    if (total > budgetBytes) {
        kept.sortBy { it.first.lastModified() }
        for ((entry, size) in kept) {
            if (total <= budgetBytes) break
            if (entry.deleteRecursively()) {
                deleted++
                total -= size
            }
        }
    }
    return deleted
}

private fun File.sizeOfTree(): Long =
    if (isFile) length() else listFiles()?.sumOf { it.sizeOfTree() } ?: 0L
