package com.hyalos.player.info

import androidx.annotation.StringRes
import com.hyalos.player.R
import com.hyalos.player.ui.browser.BrowserItem
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * One labelled line of the info dialog.
 *
 * A null [value] means the label is the whole statement — "read-only", where
 * "yes" would add nothing. Distinct from an empty section, which means there is
 * nothing of that kind at all.
 */
data class InfoRow(@StringRes val label: Int, val value: String?)

/**
 * A titled group of lines.
 *
 * **An empty group is not an absent one** — it means there is nothing of that
 * kind, and the dialog says so. "Audio: none" is the answer to why a film is
 * silent, which is half of why anyone opens this at all.
 */
data class InfoSection(@StringRes val title: Int, val rows: List<InfoRow>)

/**
 * Everything the dialog shows, assembled from what the listing already knew and
 * whatever the probe brought back.
 *
 * Pure but for the two formatters handed in: sizes and dates are Android's to
 * phrase (`Formatter`, `DateUtils`), and taking them as parameters is what lets
 * the shape of the dialog be tested without an emulator.
 *
 * [media] is null both while reading and when the read failed. The file's own
 * lines are worth showing either way — they cost nothing and are true — so the
 * sections are the same, and the dialog says separately which of the two it is.
 */
internal fun infoSections(
    item: BrowserItem,
    serverName: String,
    path: String,
    media: ProbedMedia?,
    formatSize: (Long) -> String,
    formatDate: (Long) -> String,
    /** Only known while something is playing; null in the browser. */
    decode: DecodeFacts? = null,
): List<InfoSection> {
    val sections = mutableListOf(
        InfoSection(
            title = R.string.info_section_file,
            rows = buildList {
                add(InfoRow(R.string.info_name, item.name))
                // Where it lives, which is the other half of "which file is
                // this" — the path alone does not say which NAS.
                add(InfoRow(R.string.info_server, serverName))
                add(InfoRow(R.string.info_path, path))
                item.size?.let { add(InfoRow(R.string.info_size, formatSize(it))) }
                item.modifiedMs?.let { add(InfoRow(R.string.info_modified, formatDate(it))) }
                // Absent where the server keeps no such time; SMB servers vary,
                // and an empty line is worse than no line.
                item.createdMs?.let { add(InfoRow(R.string.info_created, formatDate(it))) }
                item.accessedMs?.let { add(InfoRow(R.string.info_accessed, formatDate(it))) }
                // Only when it is true: everything else is not read-only, and
                // saying so on every file is noise.
                if (item.readOnly) add(InfoRow(R.string.info_read_only, null))
                // The file's own average — the number that explains a film which
                // stutters. Always an estimate, since no container states it, so
                // it always wears the sign that says so.
                averageBitrate(item.size, media?.durationMs)?.let {
                    add(InfoRow(R.string.info_total_bitrate, "≈ ${bitrateText(it)}"))
                }
            },
        ),
    )

    if (media != null) {
        sections += InfoSection(
            title = R.string.info_section_container,
            rows = buildList {
                mimeLabel(media.containerMimeType)?.let { add(InfoRow(R.string.info_container, it)) }
                media.durationMs?.let { add(InfoRow(R.string.info_duration, durationText(it))) }
            },
        )

        for ((kind, title) in TRACK_SECTIONS) {
            sections += InfoSection(title, media.tracks.filter { it.kind == kind }.flatMap { it.rows() })
        }
    }

    if (decode != null) {
        sections += InfoSection(R.string.info_section_decode, decode.rows())
    }

    return sections
}

/**
 * What the player is doing, as opposed to what the file contains.
 *
 * The decoder's name and how it works are two rows rather than one: this
 * dialog's values are strings the file reported — names, numbers, mime types —
 * and "hardware" is a word from the string table, which a value cannot be. The
 * word stands alone under the decoder it describes, the way "read-only" does in
 * the file section.
 */
private fun DecodeFacts.rows(): List<InfoRow> = buildList {
    for ((label, decoder) in listOf(R.string.info_video_decoder to video, R.string.info_audio_decoder to audio)) {
        decoder ?: continue
        add(InfoRow(label, decoder.name))
        // Nothing when the name could not be placed — see `DecoderFact`.
        decoder.how()?.let { add(InfoRow(it, null)) }
    }
    // Shown even at zero: "nothing was dropped" is an answer, and the row's
    // absence would read as the counter being unavailable.
    droppedFrames?.let { add(InfoRow(R.string.info_dropped_frames, it.toString())) }
}

/** The word for how a decoder works, or nothing when we could not place it. */
@StringRes
private fun DecoderFact.how(): Int? = when (hardware) {
    true -> R.string.info_decoder_hardware
    false -> R.string.info_decoder_software
    null -> null
}

private val TRACK_SECTIONS = listOf(
    TrackInfo.Kind.VIDEO to R.string.info_section_video,
    TrackInfo.Kind.AUDIO to R.string.info_section_audio,
    TrackInfo.Kind.TEXT to R.string.info_section_subtitle,
)

private fun TrackInfo.rows(): List<InfoRow> = buildList {
    // The codec string is folded in beside the name rather than given its own
    // line: it is the same fact with more precision — `avc1.640028` is H.264
    // High@4.0 — and belongs where the name it refines is.
    val name = mimeLabel(mimeType)
    when {
        name == null -> codecs?.let { add(InfoRow(R.string.info_codec, it)) }
        codecs == null -> add(InfoRow(R.string.info_codec, name))
        else -> add(InfoRow(R.string.info_codec, "$name · $codecs"))
    }

    if (width != null && height != null) {
        add(InfoRow(R.string.info_resolution, "$width×$height"))
    }
    frameRate?.let { add(InfoRow(R.string.info_frame_rate, frameRateText(it))) }
    // Only when the container states it. The guesswork lives in the file section
    // as one total, rather than repeated per track as if it were measured.
    bitrate?.let { add(InfoRow(R.string.info_bitrate, bitrateText(it.toLong()))) }
    channelCount?.let { add(InfoRow(R.string.info_channels, it.toString())) }
    sampleRate?.let { add(InfoRow(R.string.info_sample_rate, sampleRateText(it))) }
    language?.takeIf { it.isNotBlank() && it != "und" }?.let {
        add(InfoRow(R.string.info_language, it))
    }
    // Only when this device says it cannot play it, which is the answer to "why
    // is there no sound" — and saying "supported" on every line is noise. Null
    // in the browser, where nothing has asked the device yet.
    if (supported == false) add(InfoRow(R.string.info_track_unsupported, null))
}

/** Bytes over seconds, in bits per second. Null when either is missing. */
private fun averageBitrate(size: Long?, durationMs: Long?): Long? {
    if (size == null || durationMs == null || durationMs <= 0) return null
    return size * 8 * 1000 / durationMs
}

internal fun bitrateText(bitsPerSecond: Long): String {
    val mbps = bitsPerSecond / 1_000_000.0
    return if (mbps >= 1) {
        String.format(Locale.US, "%.1f Mbps", mbps)
    } else {
        String.format(Locale.US, "%.0f kbps", bitsPerSecond / 1000.0)
    }
}

/** `0:30` or `1:23:45`. Not the countdown form the player's readout uses. */
internal fun durationText(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

internal fun frameRateText(framesPerSecond: Float): String {
    // Whole numbers lose the decimal — "30 fps", not "30.0 fps" — while anything
    // else keeps two places, which is what makes 23.98 and 29.97 legible as the
    // film and NTSC rates they are.
    val rounded = framesPerSecond.roundToLong()
    return if (abs(framesPerSecond - rounded) < 0.01f) {
        "$rounded fps"
    } else {
        String.format(Locale.US, "%.2f fps", framesPerSecond)
    }
}

internal fun sampleRateText(hertz: Int): String =
    String.format(Locale.US, "%.1f kHz", hertz / 1000.0)
