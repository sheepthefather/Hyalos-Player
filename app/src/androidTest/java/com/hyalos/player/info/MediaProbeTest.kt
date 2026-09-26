package com.hyalos.player.info

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hyalos.player.kernel.shutdown
import com.hyalos.player.thumbnails.ThumbnailSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.krystallos_ffi.ConnectRequest
import uniffi.krystallos_ffi.Kernel
import uniffi.krystallos_ffi.Session

/**
 * Establishes the thing the file-info dialog rests on: that `MetadataRetriever`
 * reads the tracks of a file **over SMB**, through `KrystallosDataSource`.
 *
 * It is not obvious from the API. The retriever is documented to work on a
 * `MediaItem` with a `MediaSource.Factory`, which is exactly what the thumbnails
 * already do, so it ought to work — but "ought to" is not a foundation, and the
 * fallback if it does not is writing an `ExtractorOutput` by hand, which is a
 * different size of change. Hence a test before the dialog.
 *
 * The two fixtures are picked to cover both shapes: `测试影片 1080p.webm` has a
 * video track and **no audio at all** (the player's own options menu says
 * "Audio / None" for it), `sample.mp4` has one of each. The expected codecs are
 * not guesses — they are the markers the containers carry in plain text:
 * `V_VP8` in the webm, `avc1` and `mp4a` in the mp4.
 *
 * Needs the host's `smbd` up with the `media` share, as the emulator's
 * `10.0.2.2`; `connectedAndroidTest` uninstalls the app when it finishes, so
 * this cannot rely on anything pushed by hand.
 */
@RunWith(AndroidJUnit4::class)
class MediaProbeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var scope: CoroutineScope
    private lateinit var session: Session

    /** The id is decoration: `KrystallosDataSource` addresses files by path alone. */
    private lateinit var source: ThumbnailSource

    @Before
    fun connect() = runBlocking {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        session = Kernel().connect(
            ConnectRequest(
                uri = "smb://10.0.2.2/$SHARE",
                username = null,
                password = null,
                domain = null,
                smbSeal = false,
            ),
        )
        source = ThumbnailSource({ session }, scope)
    }

    @After
    fun disconnect() = runBlocking {
        session.shutdown()
        scope.cancel()
    }

    @Test
    fun readsAVideoTrackAndFindsNoAudioWhereThereIsNone() = runBlocking {
        val media = MediaProbe(context).probe(source, SERVER_ID, WEBM)
        println("PROBE webm -> $media")

        val video = media.tracks.single { it.kind == TrackInfo.Kind.VIDEO }
        assertEquals("video/x-vnd.on2.vp8", video.mimeType)
        // As a pair, in one string: the failure then shows what came back
        // instead, and neither value can be null without the test saying so.
        assertEquals("1920x1080", "${video.width}x${video.height}")
        assertNotNull("container", media.containerMimeType)
        // A range rather than a number: the exact millisecond is the container's
        // business, and 30 seconds is what the player shows for this file. A
        // missing duration fails here too, with the null in the message.
        assertTrue("duration was ${media.durationMs}", media.durationMs?.let { it in 29_000L..31_000L } == true)

        assertTrue(
            "found an audio track in a file that has none: ${media.tracks}",
            media.tracks.none { it.kind == TrackInfo.Kind.AUDIO },
        )
    }

    @Test
    fun readsBothTracksOfAnMp4() = runBlocking {
        val media = MediaProbe(context).probe(source, SERVER_ID, MP4)
        println("PROBE mp4 -> $media")

        val video = media.tracks.single { it.kind == TrackInfo.Kind.VIDEO }
        val audio = media.tracks.single { it.kind == TrackInfo.Kind.AUDIO }
        assertEquals("video/avc", video.mimeType)
        assertEquals("audio/mp4a-latm", audio.mimeType)
        assertNotNull("container", media.containerMimeType)
    }

    private companion object {
        const val SHARE = "media"
        const val SERVER_ID = "media"
        const val WEBM = "/movies/测试影片 1080p.webm"
        const val MP4 = "/movies/sample.mp4"
    }
}
