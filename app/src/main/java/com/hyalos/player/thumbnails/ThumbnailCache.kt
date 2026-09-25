package com.hyalos.player.thumbnails

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Encoded thumbnails on disk, one file per video.
 *
 * Held in `cacheDir`, which the system may clear at any time — that is
 * acceptable, a cleared thumbnail is simply extracted again. It is deliberately
 * not in `filesDir`, which the user sees as app storage.
 *
 * # Eviction
 *
 * The running total is kept **in memory** and updated on every write and delete,
 * because recomputing it would mean listing thousands of files on every single
 * thumbnail. The directory itself remains the source of truth: there is no
 * `key -> size` index file, since an index and the files it describes drift
 * apart (killed mid-write, cleared by the system) and can never be reconciled.
 * File modification time doubles as a last-used stamp, so eviction removes what
 * has not been looked at for longest rather than what was written longest ago.
 *
 * When the total exceeds the limit it is trimmed to [LOW_WATER] of it, not
 * merely to just-under: trimming to exactly the limit means the *next* write is
 * over again, turning every write into a delete.
 */
class ThumbnailCache(private val dir: File) {

    private val lock = Mutex()

    /** Bytes currently held. Only meaningful once [measured] is set. */
    private var totalBytes = 0L
    private var measured = false

    private var limitBytes: Long = DEFAULT_LIMIT_BYTES

    /** Set when the limit is zero, which means thumbnails are switched off. */
    val isDisabled: Boolean get() = limitBytes <= 0

    /**
     * Change the size limit, evicting immediately if it shrank.
     *
     * Immediately because the user asked for it and expects to see the space
     * come back, not to wait for the next write.
     */
    suspend fun setLimit(bytes: Long) = withContext(Dispatchers.IO) {
        lock.withLock {
            limitBytes = bytes
            measureIfNeeded()
            trimIfNeeded()
        }
    }

    /** The encoded image for [key], or `null` if it is not cached. */
    suspend fun get(key: ThumbnailKey): Bitmap? =
        getBytes(key)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }

    /** Store [bitmap] under [key]; encoding failures are swallowed (a missing thumbnail is cosmetic). */
    suspend fun put(key: ThumbnailKey, bitmap: Bitmap) {
        val out = java.io.ByteArrayOutputStream()
        // JPEG rather than WebP: the lossy WebP encoder needs API 30 and this app
        // supports 29. Thumbnails carry no alpha worth keeping.
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) return
        putBytes(key, out.toByteArray())
    }

    /**
     * The stored bytes for [key], or `null`.
     *
     * Bytes rather than a `Bitmap` so that everything above can be exercised on
     * the JVM: `Bitmap` is an Android class that unit tests cannot instantiate,
     * and the eviction rules are the part most worth testing.
     */
    suspend fun getBytes(key: ThumbnailKey): ByteArray? = withContext(Dispatchers.IO) {
        if (isDisabled) return@withContext null
        val file = imageFile(key)
        if (!file.isFile) return@withContext null
        // Stamp it as used now, so eviction sees real usage order rather than
        // write order.
        file.setLastModified(System.currentTimeMillis())
        runCatching { file.readBytes() }.getOrNull()
    }

    suspend fun putBytes(key: ThumbnailKey, bytes: ByteArray) = withContext(Dispatchers.IO) {
        lock.withLock {
            measureIfNeeded()
            if (isDisabled) return@withLock

            // Written through a temporary file and renamed, so a reader can
            // never see a half-written image.
            val temp = File(dir, "${key.hash}.tmp")
            val target = imageFile(key)
            try {
                dir.mkdirs()
                temp.writeBytes(bytes)
                val written = temp.length()
                // An image larger than the entire budget would be evicted by the
                // trim below on every write. Refuse it instead.
                if (written > limitBytes) {
                    temp.delete()
                    return@withLock
                }
                if (target.isFile) totalBytes -= target.length()
                if (!temp.renameTo(target)) throw IOException("rename to ${target.name} failed")
                totalBytes += written
                // Any negative marker is now wrong: there is a frame after all.
                deleteMarker(key)
            } catch (e: IOException) {
                temp.delete()
            }
            trimIfNeeded()
        }
    }

    /** Record that [key] has no extractable frame, so it is not retried on every scroll. */
    suspend fun markMissing(key: ThumbnailKey) = withContext(Dispatchers.IO) {
        lock.withLock {
            if (isDisabled) return@withLock
            runCatching {
                dir.mkdirs()
                markerFile(key).writeBytes(ByteArray(0))
            }
            measureIfNeeded(markStale = true)
            trimIfNeeded()
        }
    }

    /** Whether [key] is known to have no extractable frame. */
    suspend fun isMissing(key: ThumbnailKey): Boolean = withContext(Dispatchers.IO) {
        !isDisabled && markerFile(key).isFile
    }

    /** Bytes on disk, counting markers at a nominal size (see below). */
    suspend fun usage(): Long = withContext(Dispatchers.IO) {
        lock.withLock {
            measureIfNeeded()
            totalBytes
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        lock.withLock {
            dir.listFiles()?.forEach { it.delete() }
            totalBytes = 0
            measured = true
        }
    }

    private fun imageFile(key: ThumbnailKey) = File(dir, "${key.hash}.$IMAGE_SUFFIX")

    private fun markerFile(key: ThumbnailKey) = File(dir, "${key.hash}.$MARKER_SUFFIX")

    private fun deleteMarker(key: ThumbnailKey) {
        val marker = markerFile(key)
        if (marker.isFile && marker.delete()) totalBytes -= MARKER_BYTES
    }

    private fun measureIfNeeded(markStale: Boolean = false) {
        if (measured && !markStale) return
        var total = 0L
        dir.listFiles()?.forEach { file ->
            total += if (file.name.endsWith(".$MARKER_SUFFIX")) MARKER_BYTES else file.length()
        }
        totalBytes = total
        measured = true
    }

    /**
     * Delete least-recently-used entries until the total is under the target.
     *
     * A limit of zero means the cache is switched off, and that also means
     * emptying it — which is why this cannot simply return early when the limit
     * is zero.
     */
    private fun trimIfNeeded() {
        val target = if (limitBytes <= 0) 0L else (limitBytes * LOW_WATER).toLong()
        if (totalBytes <= target) return
        val entries = dir.listFiles()?.toMutableList() ?: return
        // Oldest modification time first: that is the least recently *used*,
        // because `get` stamps the file when it is read.
        entries.sortBy { it.lastModified() }
        for (file in entries) {
            if (totalBytes <= target) break
            val weight = if (file.name.endsWith(".$MARKER_SUFFIX")) MARKER_BYTES else file.length()
            if (file.delete()) totalBytes -= weight
        }
    }

    companion object {
        const val DEFAULT_LIMIT_BYTES = 100L * 1024 * 1024

        private const val IMAGE_SUFFIX = "jpg"
        private const val MARKER_SUFFIX = "none"
        private const val JPEG_QUALITY = 85

        /**
         * Markers hold no image data but still occupy a directory entry, so they
         * are counted at a nominal size. Counting them as zero would mean they
         * never contribute to the total and are therefore never evicted, and
         * they would accumulate for every unreadable file the user ever scrolls past.
         */
        private const val MARKER_BYTES = 1024L

        /** Trim to this fraction of the limit, so the next write does not evict again. */
        private const val LOW_WATER = 0.9
    }
}
