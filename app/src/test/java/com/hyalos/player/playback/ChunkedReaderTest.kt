package com.hyalos.player.playback

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkedReaderTest {
    /** Serves [data] and counts how often it was asked — the number the buffer exists to reduce. */
    private class CountingReader(private val data: ByteArray) : RandomReader {
        var reads = 0
        override val length = data.size.toLong()
        override suspend fun readAt(offset: Long, len: Int): ByteArray {
            reads++
            val from = offset.coerceAtMost(length).toInt()
            return data.copyOfRange(from, minOf(from + len, data.size))
        }
    }

    private fun payload(n: Int) = ByteArray(n) { (it % 251).toByte() }

    /** Read [len] bytes at [position] the way the DataSource does: try, fill on miss, try again. */
    private suspend fun ChunkedReader.read(src: RandomReader, position: Long, dst: ByteArray, len: Int): Int {
        val n = tryRead(src, position, dst, 0, len)
        if (n != ChunkedReader.MISS) return n
        if (!fill(src, position)) return -1
        return tryRead(src, position, dst, 0, len)
    }

    @Test
    fun `a million one-byte reads cost one fetch per window`() = runTest {
        // The pathological pattern from the field: an extractor reading one
        // byte at a time. Without the window this is a million round-trips.
        val data = payload(1024 * 1024)
        val src = CountingReader(data)
        val reader = ChunkedReader()
        val out = ByteArray(data.size)
        val one = ByteArray(1)
        for (i in data.indices) {
            assertEquals(1, reader.read(src, i.toLong(), one, 1))
            out[i] = one[0]
        }
        assertArrayEquals(data, out)
        assertEquals(1, src.reads)
    }

    @Test
    fun `sequential reads across windows return every byte in order`() = runTest {
        val data = payload(10_000)
        val src = CountingReader(data)
        val reader = ChunkedReader(windowSize = 4096)
        val out = mutableListOf<Byte>()
        val buf = ByteArray(1000)
        var pos = 0L
        while (true) {
            val n = reader.read(src, pos, buf, buf.size)
            if (n == -1) break
            out += buf.take(n)
            pos += n
        }
        assertArrayEquals(data, out.toByteArray())
        // Three windows of data, plus the probe that finds the end.
        assertEquals(4, src.reads)
    }

    @Test
    fun `a read never spans past the window`() = runTest {
        // A short answer at a window boundary is fine — DataSource.read may
        // return fewer bytes than asked — but it must not invent bytes.
        val data = payload(8192)
        val reader = ChunkedReader(windowSize = 4096)
        val src = CountingReader(data)
        assertTrue(reader.fill(src, 0))

        val buf = ByteArray(1000)
        assertEquals(96, reader.tryRead(src, 4000, buf, 0, 1000))
        assertArrayEquals(data.copyOfRange(4000, 4096), buf.copyOf(96))
        // The next byte is past the window: a miss, not a stale copy.
        assertEquals(ChunkedReader.MISS, reader.tryRead(src, 4096, buf, 0, 1000))
    }

    @Test
    fun `seeking back inside the window does not refetch`() = runTest {
        val data = payload(64 * 1024)
        val src = CountingReader(data)
        val reader = ChunkedReader()
        val buf = ByteArray(16)
        reader.read(src, 0, buf, 16)
        for (pos in listOf(30_000L, 5L, 60_000L, 0L)) {
            assertEquals(16, reader.read(src, pos, buf, 16))
            assertArrayEquals(data.copyOfRange(pos.toInt(), pos.toInt() + 16), buf)
        }
        assertEquals(1, src.reads)
    }

    @Test
    fun `a position outside the window misses`() {
        val reader = ChunkedReader()
        val src = CountingReader(payload(10))
        assertEquals(ChunkedReader.MISS, reader.tryRead(src, 0, ByteArray(4), 0, 4))
    }

    @Test
    fun `a different reader never sees another file's window`() = runTest {
        val a = CountingReader(ByteArray(100) { 1 })
        val b = CountingReader(ByteArray(100) { 2 })
        val reader = ChunkedReader()
        val buf = ByteArray(10)
        reader.read(a, 0, buf, 10)
        assertEquals(ChunkedReader.MISS, reader.tryRead(b, 0, buf, 0, 10))
        reader.read(b, 0, buf, 10)
        assertTrue(buf.all { it == 2.toByte() })
    }

    @Test
    fun `end of file is reported, not an empty success`() = runTest {
        val src = CountingReader(payload(10))
        val reader = ChunkedReader()
        assertFalse(reader.fill(src, 10))
        assertEquals(-1, reader.read(src, 10, ByteArray(4), 4))
    }

    @Test
    fun `a zero-length read is answered without touching the source`() {
        val src = CountingReader(payload(10))
        assertEquals(0, ChunkedReader().tryRead(src, 0, ByteArray(0), 0, 0))
        assertEquals(0, src.reads)
    }
}
