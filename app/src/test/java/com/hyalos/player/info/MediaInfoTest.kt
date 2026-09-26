package com.hyalos.player.info

import com.hyalos.player.R
import com.hyalos.player.ui.browser.BrowserItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaInfoTest {

    private val size = { bytes: Long -> "$bytes B" }
    private val date = { ms: Long -> "t$ms" }

    private fun videoTrack(
        mimeType: String? = "video/x-vnd.on2.vp8",
        codecs: String? = null,
        width: Int? = 1920,
        height: Int? = 1080,
        frameRate: Float? = 30f,
        bitrate: Int? = null,
        channelCount: Int? = null,
        sampleRate: Int? = null,
        language: String? = null,
        kind: TrackInfo.Kind = TrackInfo.Kind.VIDEO,
    ) = TrackInfo(
        kind = kind,
        mimeType = mimeType,
        codecs = codecs,
        width = width,
        height = height,
        frameRate = frameRate,
        bitrate = bitrate,
        channelCount = channelCount,
        sampleRate = sampleRate,
        language = language,
    )

    private fun sections(
        item: BrowserItem,
        media: ProbedMedia?,
    ) = infoSections(
        item = item,
        serverName = "TestNAS",
        path = "/movies/film.webm",
        media = media,
        formatSize = size,
        formatDate = date,
    )

    private fun List<InfoSection>.rows(title: Int) = first { it.title == title }.rows
    private fun List<InfoRow>.value(label: Int) = firstOrNull { it.label == label }?.value
    private fun List<InfoRow>.labels() = map { it.label }

    @Test
    fun `a file that cannot be read still shows what the listing knew`() {
        val sections = sections(Film, media = null)

        // One section, and it is complete on its own: these lines cost nothing
        // and stay true whether or not the header could be read.
        assertEquals(1, sections.size)
        assertEquals(R.string.info_section_file, sections.single().title)
        assertEquals("film.webm", sections.single().rows.value(R.string.info_name))
        assertEquals("/movies/film.webm", sections.single().rows.value(R.string.info_path))
    }

    @Test
    fun `a track with no audio says so rather than saying nothing`() {
        val sections = sections(Film, ProbedMedia("video/webm", 30_000, listOf(videoTrack())))

        // Present and empty. "Audio: none" is the answer to why a film is
        // silent — dropping the section would leave the reader wondering
        // whether it was looked for at all.
        val audio = sections.first { it.title == R.string.info_section_audio }
        assertEquals(emptyList<InfoRow>(), audio.rows)
        assertTrue("video was dropped", sections.rows(R.string.info_section_video).isNotEmpty())
    }

    @Test
    fun `the total bitrate is the file over its length, and admits it is a guess`() {
        // 500 KB over 30 s is about 133 kbps.
        val sections = sections(Film.copy(size = 500_000), ProbedMedia(null, 30_000, emptyList()))

        val total = sections.rows(R.string.info_section_file).value(R.string.info_total_bitrate)
        assertTrue("was $total", total!!.startsWith("≈ "))
        assertTrue("was $total", total.contains("kbps"))
    }

    @Test
    fun `no length means no total bitrate, not a wrong one`() {
        val sections = sections(Film.copy(size = 500_000), ProbedMedia(null, null, emptyList()))
        assertNull(sections.rows(R.string.info_section_file).value(R.string.info_total_bitrate))

        // And when the file could not be read at all, either.
        assertNull(sections(Film.copy(size = 500_000), null).rows(R.string.info_section_file).value(R.string.info_total_bitrate))
    }

    @Test
    fun `times and the read-only flag appear only when there is something to say`() {
        val bare = sections(Film, media = null).rows(R.string.info_section_file)
        assertTrue(R.string.info_created !in bare.labels())
        assertTrue(R.string.info_accessed !in bare.labels())
        assertTrue(R.string.info_read_only !in bare.labels())

        val full = sections(
            Film.copy(createdMs = 1, accessedMs = 2, readOnly = true),
            media = null,
        ).rows(R.string.info_section_file)
        assertEquals("t1", full.value(R.string.info_created))
        assertEquals("t2", full.value(R.string.info_accessed))
        // The label is the whole statement; "yes" would add nothing.
        assertNull(full.value(R.string.info_read_only))
    }

    @Test
    fun `a codec string is folded in beside its name`() {
        val withProfile = sections(
            Film,
            ProbedMedia(null, null, listOf(videoTrack(mimeType = "video/avc", codecs = "avc1.640028"))),
        )
        assertEquals("H.264 (AVC) · avc1.640028", withProfile.rows(R.string.info_section_video).value(R.string.info_codec))

        // An unrecognised mime is shown as it is, and a missing one leaves the
        // codec string to stand alone rather than printing a blank name.
        val unknown = sections(Film, ProbedMedia(null, null, listOf(videoTrack(mimeType = "video/x-strange"))))
        assertEquals("video/x-strange", unknown.rows(R.string.info_section_video).value(R.string.info_codec))

        val nameless = sections(Film, ProbedMedia(null, null, listOf(videoTrack(mimeType = null, codecs = "zzz"))))
        assertEquals("zzz", nameless.rows(R.string.info_section_video).value(R.string.info_codec))
    }

    @Test
    fun `a track's own bitrate is shown only when the container stated one`() {
        val stated = sections(Film, ProbedMedia(null, null, listOf(videoTrack(bitrate = 4_300_000))))
        assertEquals("4.3 Mbps", stated.rows(R.string.info_section_video).value(R.string.info_bitrate))

        val unstated = sections(Film, ProbedMedia(null, null, listOf(videoTrack(bitrate = null))))
        assertNull(unstated.rows(R.string.info_section_video).value(R.string.info_bitrate))
        // The estimate lives in the file section instead, once, rather than
        // being repeated here as though it had been measured.
        assertNull(unstated.rows(R.string.info_section_file).value(R.string.info_bitrate))
    }

    @Test
    fun `an undetermined language is left out`() {
        val und = sections(Film, ProbedMedia(null, null, listOf(videoTrack(kind = TrackInfo.Kind.AUDIO, language = "und"))))
        assertNull(und.rows(R.string.info_section_audio).value(R.string.info_language))

        val eng = sections(Film, ProbedMedia(null, null, listOf(videoTrack(kind = TrackInfo.Kind.AUDIO, language = "eng"))))
        assertEquals("eng", eng.rows(R.string.info_section_audio).value(R.string.info_language))
    }

    @Test
    fun `resolution, frame rate and sample rate read the way people write them`() {
        assertEquals("1920×1080", sections(Film, ProbedMedia(null, null, listOf(videoTrack())))
            .rows(R.string.info_section_video).value(R.string.info_resolution))

        assertEquals("0:30", durationText(30_000))
        assertEquals("1:23:45", durationText(5_025_000))
        assertEquals("4.3 Mbps", bitrateText(4_300_000))
        assertEquals("133 kbps", bitrateText(133_000))
        assertEquals("30 fps", frameRateText(30f))
        assertEquals("23.98 fps", frameRateText(23.976f))
        assertEquals("48.0 kHz", sampleRateText(48_000))
    }

    private companion object {
        val Film = BrowserItem(
            name = "film.webm",
            kind = BrowserItem.Kind.VIDEO,
            size = null,
            modifiedMs = null,
        )
    }
}
