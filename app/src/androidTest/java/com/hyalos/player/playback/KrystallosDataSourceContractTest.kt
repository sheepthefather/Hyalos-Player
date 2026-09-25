package com.hyalos.player.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.test.utils.DataSourceContractTest
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.collect.ImmutableList
import org.junit.runner.RunWith
import uniffi.krystallos_ffi.KernelException

/**
 * Media3's own contract suite, run against [KrystallosDataSource].
 *
 * The kernel is replaced by in-memory files behind [ReaderSource], which is
 * what the DataSource depends on for exactly this reason. What is under test
 * is the DataSource's side of the contract — positions, lengths, end of input,
 * transfer-listener calls, `getUri` after close — not the network.
 *
 * On a device because `DataSpec` uses `android.net.Uri`.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class KrystallosDataSourceContractTest : DataSourceContractTest() {

    private val files = mapOf(
        "/small.bin" to bytes(64),
        // Crosses the 1 MiB window twice, so misses and refills are exercised.
        "/large.bin" to bytes(ChunkedReader.DEFAULT_WINDOW * 5 / 2),
        // A name that a hand-built URI string would mangle into a fragment.
        "/影片/第 1 集 #1.mkv" to bytes(4096),
    )

    private val source = object : ReaderSource {
        override suspend fun reader(path: String): RandomReader {
            val data = files[path] ?: throw KernelException.NotFound(path)
            return object : RandomReader {
                override val length = data.size.toLong()
                override suspend fun readAt(offset: Long, len: Int): ByteArray {
                    val from = offset.coerceAtMost(length).toInt()
                    return data.copyOfRange(from, minOf(from + len, data.size))
                }
            }
        }

        override suspend fun invalidate() {}
    }

    override fun createDataSource(): DataSource = KrystallosDataSource(source)

    override fun getTestResources(): ImmutableList<TestResource> = ImmutableList.copyOf(
        files.map { (path, data) ->
            TestResource.Builder()
                .setName(path)
                .setUri(KrystallosUri.of("server", path))
                .setExpectedBytes(data)
                .build()
        },
    )

    override fun getNotFoundUri(): Uri = KrystallosUri.of("server", "/missing.mkv")

    private fun bytes(n: Int) = ByteArray(n) { (it % 251).toByte() }
}
