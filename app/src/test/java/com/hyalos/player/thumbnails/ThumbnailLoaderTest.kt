package com.hyalos.player.thumbnails

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicInteger

/**
 * The scheduling rules, exercised with a fake [FrameSource].
 *
 * The successful path cannot be covered here: it produces a `Bitmap`, which is
 * an Android class a JVM test cannot instantiate. Everything that decides *when*
 * an extraction happens — which is what these rules are — needs no bitmap at
 * all, so it is tested; the pixels themselves are covered by the on-device
 * extraction test and the end-to-end run.
 */
class ThumbnailLoaderTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val key = ThumbnailKey("srv", "/movies/a.mkv", 1024, 0)

    private fun cache() = ThumbnailCache(temp.newFolder("thumbs"))

    /** Trailing-lambda friendly: `frames` is not the last constructor parameter. */
    private fun loader(cache: ThumbnailCache, frames: suspend (ThumbnailKey) -> Extraction) =
        ThumbnailLoader(cache, FrameSource { frames(it) })

    @Test
    fun `a transient failure is not remembered`() = runTest {
        // Caching one dropped connection would leave a whole directory without
        // thumbnails until the cache was cleared by hand.
        val calls = AtomicInteger()
        val loader = loader(cache()) { calls.incrementAndGet(); Extraction.Transient }

        assertNull(loader.load(key))
        assertNull(loader.load(key))

        assertEquals("the failure was written down", 2, calls.get())
    }

    @Test
    fun `an unsupported file is remembered so it is not retried forever`() = runTest {
        val calls = AtomicInteger()
        val loader = loader(cache()) { calls.incrementAndGet(); Extraction.Unsupported }

        assertNull(loader.load(key))
        assertNull(loader.load(key))
        assertNull(loader.load(key))

        assertEquals("an unreadable film was extracted more than once", 1, calls.get())
    }

    @Test
    fun `concurrent callers for the same film do not extract it twice`() = runTest {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val loader = loader(cache()) {
            val now = active.incrementAndGet()
            peak.updateAndGet { maxOf(it, now) }
            delay(10)
            active.decrementAndGet()
            Extraction.Transient
        }

        coroutineScope {
            val first = async { loader.load(key) }
            val second = async { loader.load(key) }
            first.await()
            second.await()
        }

        assertEquals("the same film was extracted concurrently", 1, peak.get())
    }

    /**
     * Held, not timed.
     *
     * The first film is parked inside its extraction, and the second has to get
     * through on its own while it sits there. An earlier version counted how
     * many extractions were ever in flight at once and asserted a peak of two —
     * which is a race, not a proof: `ThumbnailCache` reads the disk on
     * `Dispatchers.IO`, and `runTest` advances its *virtual* clock the moment the
     * test scheduler runs dry, so on a slow machine the first film's `delay`
     * could elapse before the second had finished its file check and the peak
     * came out as one. It passed here and failed on CI.
     *
     * Nothing below can pass by accident: if an extraction held anything global,
     * the second film would never return, the test would never complete, and
     * `runTest` would fail it on its timeout.
     */
    @Test
    fun `different films are not serialised behind each other`() = runTest {
        val other = ThumbnailKey("srv", "/movies/b.mkv", 1024, 0)
        val extracted = AtomicInteger()
        val firstIsExtracting = CompletableDeferred<Unit>()
        val holdTheFirst = CompletableDeferred<Unit>()
        val loader = loader(cache()) { requested ->
            extracted.incrementAndGet()
            if (requested == key) {
                firstIsExtracting.complete(Unit)
                holdTheFirst.await()
            }
            Extraction.Transient
        }

        val first = async { loader.load(key) }
        firstIsExtracting.await()

        // The second film, from outside any coroutine the first could be queued
        // behind: it must run its own extraction to completion right here.
        loader.load(other)

        holdTheFirst.complete(Unit)
        first.await()

        assertEquals("the second film was never extracted", 2, extracted.get())
    }

    @Test
    fun `switching thumbnails off stops extraction entirely`() = runTest {
        val calls = AtomicInteger()
        val cache = cache()
        cache.setLimit(0)
        val loader = loader(cache) { calls.incrementAndGet(); Extraction.Transient }

        assertNull(loader.load(key))

        assertEquals("a frame was pulled with thumbnails switched off", 0, calls.get())
    }

    @Test
    fun `a known-missing film is not extracted again`() = runTest {
        val calls = AtomicInteger()
        val cache = cache()
        cache.markMissing(key)
        val loader = loader(cache) { calls.incrementAndGet(); Extraction.Transient }

        assertNull(loader.load(key))

        assertEquals(0, calls.get())
    }
}
