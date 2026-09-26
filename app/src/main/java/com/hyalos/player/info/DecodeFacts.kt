package com.hyalos.player.info

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil

/**
 * Which decoder is doing the work, and whether it is hardware.
 *
 * [hardware] is null when the decoder that reported itself is not among those
 * Media3 lists for that mime — a name we cannot place. Null rather than a guess,
 * on the same rule as an unrecognised mime type: the name is still exact
 * information, and a wrong "hardware" would be worse than no answer.
 */
data class DecoderFact(val name: String, val hardware: Boolean?)

/**
 * What the *player* is doing, as opposed to what the file says about itself.
 *
 * Kept apart from [ProbedMedia] because the two come from different places and
 * can disagree: the file declares its tracks, the player reports what it
 * actually opened and with what.
 */
data class DecodeFacts(
    val video: DecoderFact?,
    val audio: DecoderFact?,
    /** Cumulative frames the renderer threw away. Null when there is no video renderer. */
    val droppedFrames: Int?,
)

/**
 * Look up a decoder the player named.
 *
 * `MediaCodecInfo.hardwareAccelerated` rather than a name-prefix rule of our
 * own: that field is the platform's `MediaCodecInfo.isHardwareAccelerated()` on
 * API 29 and up, and on anything older Media3 falls back to precisely the prefix
 * rule we would otherwise be writing a second copy of — including its list of
 * software decoders, which is longer than the obvious `c2.android.`/`omx.google.`
 * pair and has changed over the years.
 *
 * The player picks its decoder from this same list, so the name it reports is
 * nearly always in it; a miss means only that this row cannot say.
 */
@OptIn(UnstableApi::class)
internal fun decoderFact(name: String?, mimeType: String?): DecoderFact? {
    if (name.isNullOrBlank()) return null
    val known = mimeType
        ?.let { mime -> runCatching { MediaCodecUtil.getDecoderInfos(mime, false, false) }.getOrNull() }
        ?.firstOrNull { it.name == name }
    return DecoderFact(name = name, hardware = known?.hardwareAccelerated)
}
