package com.hyalos.player.thumbnails

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.inspector.frame.FrameExtractor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Guards the concurrency design: `FrameExtractor` must work from a background
 * `HandlerThread`.
 *
 * This is the assumption everything in this package rests on. The documented
 * rule is only that an instance must be reached from a *single application
 * thread*, but the extractor builds an `ExoPlayer` internally and touches
 * `Looper.getMainLooper` — which could have meant it wanted the main thread
 * instead, and would have forced a much worse design. A `HandlerThread` has a
 * Looper and pins one thread, so it satisfies either reading; this test is what
 * establishes that the first reading is the true one.
 *
 * The video is an asset rather than something pushed with `adb`, because
 * `connectedAndroidTest` uninstalls the app when it finishes and takes any
 * pushed file with it — a test that skipped itself every run would be worse
 * than no test at all.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class FrameExtractorSpikeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /**
     * The *test* APK's context, not the app's: an asset under
     * `src/androidTest/assets` is packaged into the test APK, and the two have
     * separate asset managers.
     */
    private val testContext: Context = InstrumentationRegistry.getInstrumentation().context

    @Test
    fun aThumbnailCanBeExtractedFromABackgroundHandlerThread() = runBlocking {
        // Copied out of the APK: an asset has no path, and the extractor wants a URI.
        val sample = File(context.cacheDir, "spike-sample.webm")
        testContext.assets.open("tiny.webm").use { input ->
            sample.outputStream().use { input.copyTo(it) }
        }

        val thread = HandlerThread("thumbnail-spike").apply { start() }
        val lane = Handler(thread.looper).asCoroutineDispatcher()
        try {
            val frame = withContext(lane) {
                // Build, await and close all on one thread, which is the rule.
                FrameExtractor.Builder(context, MediaItem.fromUri(sample.toURI().toString()))
                    .build()
                    .use { it.thumbnail.get() }
            }

            assertNotNull("no bitmap came back", frame.bitmap)
            // tiny.webm is 320x240. Real dimensions prove a frame was actually
            // decoded rather than an empty bitmap slipping through.
            assertEquals("decoded width", 320, frame.bitmap.width)
            assertEquals("decoded height", 240, frame.bitmap.height)

            // And it has to carry pixels: a blank frame is all zeros.
            var lit = 0
            for (y in 0 until frame.bitmap.height step 17) {
                for (x in 0 until frame.bitmap.width step 17) {
                    if (frame.bitmap.getPixel(x, y) != 0) lit++
                }
            }
            assertTrue("the frame is entirely blank", lit > 50)
        } finally {
            // Quitting the looper is what releases the lane.
            thread.quitSafely()
        }
    }
}
