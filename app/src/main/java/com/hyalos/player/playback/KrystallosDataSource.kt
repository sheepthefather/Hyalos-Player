package com.hyalos.player.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.runBlocking
import uniffi.krystallos_ffi.KernelException
import java.io.FileNotFoundException
import java.io.InterruptedIOException

/**
 * Feeds Media3 straight from the kernel — no local HTTP server in between.
 *
 * # Threading
 *
 * ExoPlayer calls `open` and `read` on its loader thread, where blocking is
 * expected, so the kernel's `suspend` calls are bridged with `runBlocking` —
 * and only on a buffer miss; see [ChunkedReader].
 *
 * When ExoPlayer cancels a load (a seek, or the player being released) it
 * interrupts that thread. `runBlocking` turns the interrupt into cancellation
 * and throws, so a read stuck on the network stops being *waited for*
 * immediately. The kernel does not see Kotlin cancellation, so the read itself
 * runs on to its own timeout; nothing here depends on it finishing.
 *
 * # Errors and retries
 *
 * Kernel failures become [DataSourceException]s with the matching Media3 error
 * code, which decides what ExoPlayer's default retry policy does:
 *
 * - `NotFound` carries a [FileNotFoundException] as its cause, which the
 *   policy recognises as fatal — no point retrying a file that is gone.
 * - `ConnectionLost` drops the connection first. The policy then retries, the
 *   retry re-opens at the same position, and re-opening reconnects: a NAS that
 *   dropped off the network for a moment heals without the user noticing.
 */
@OptIn(UnstableApi::class)
class KrystallosDataSource(private val source: ReaderSource) : BaseDataSource(/* isNetwork = */ true) {

    private val chunks = ChunkedReader()
    private var uri: Uri? = null
    private var reader: RandomReader? = null
    private var position = 0L
    private var bytesRemaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)

        val path = dataSpec.uri.path
            ?: throw DataSourceException("no path in ${dataSpec.uri}", null, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        val reader = blockingIo { source.reader(path) }
        if (dataSpec.position > reader.length) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }

        this.reader = reader
        position = dataSpec.position
        bytesRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            reader.length - position
        } else {
            dataSpec.length
        }

        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val reader = checkNotNull(reader) { "read before open" }
        val want = minOf(length.toLong(), bytesRemaining).toInt()

        var n = chunks.tryRead(reader, position, buffer, offset, want)
        if (n == ChunkedReader.MISS) {
            // The file is shorter than the size seen at open. Unusual, but the
            // honest answer is that the input ended.
            if (!blockingIo { chunks.fill(reader, position) }) return C.RESULT_END_OF_INPUT
            n = chunks.tryRead(reader, position, buffer, offset, want)
        }

        position += n
        bytesRemaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    /**
     * Forgets the position only. The file and session stay open in the
     * [ReaderSource], because ExoPlayer closes and re-opens on every seek.
     */
    override fun close() {
        uri = null
        reader = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    private fun <T> blockingIo(block: suspend () -> T): T = try {
        runBlocking {
            try {
                block()
            } catch (e: KernelException.ConnectionLost) {
                source.invalidate()
                throw e
            }
        }
    } catch (e: InterruptedException) {
        // Restore the flag for whoever interrupted us, and report it the way
        // an IO call is expected to.
        Thread.currentThread().interrupt()
        throw InterruptedIOException("read interrupted").apply { initCause(e) }
    } catch (e: KernelException) {
        throw e.toDataSourceException()
    }

    class Factory(private val source: ReaderSource) : DataSource.Factory {
        override fun createDataSource(): DataSource = KrystallosDataSource(source)
    }
}

@OptIn(UnstableApi::class)
internal fun KernelException.toDataSourceException(): DataSourceException = when (this) {
    is KernelException.NotFound ->
        DataSourceException(FileNotFoundException(path), PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
    is KernelException.PermissionDenied, is KernelException.Auth ->
        DataSourceException(this, PlaybackException.ERROR_CODE_IO_NO_PERMISSION)
    is KernelException.ConnectionLost ->
        DataSourceException(this, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
    else -> DataSourceException(this, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
}
