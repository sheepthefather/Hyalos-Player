package com.hyalos.player.info

/**
 * What to call a mime type, in words people use.
 *
 * Covers both the tracks (`video/avc`) and the containers (`video/x-matroska`):
 * the two families do not collide, and one table is easier to keep honest than
 * two that drift.
 *
 * **Anything not in the table is shown as it is** rather than labelled
 * "unknown". A mime type we have not met is still precise information — it is
 * what the file says about itself — and inventing a name for it, or hiding it
 * behind "其他", would throw away the one fact that helps someone look it up.
 */
internal fun mimeLabel(mimeType: String?): String? = when (mimeType) {
    null -> null

    // Video.
    "video/avc" -> "H.264 (AVC)"
    "video/hevc" -> "H.265 (HEVC)"
    "video/av01" -> "AV1"
    "video/x-vnd.on2.vp8" -> "VP8"
    "video/x-vnd.on2.vp9" -> "VP9"
    "video/mp4v-es" -> "MPEG-4"
    "video/mpeg2" -> "MPEG-2"
    "video/3gpp" -> "H.263"

    // Audio.
    "audio/mp4a-latm" -> "AAC"
    "audio/opus" -> "Opus"
    "audio/vorbis" -> "Vorbis"
    "audio/mpeg" -> "MP3"
    "audio/flac" -> "FLAC"
    "audio/raw" -> "PCM"
    "audio/ac3" -> "AC-3"
    "audio/eac3" -> "E-AC-3"
    "audio/true-hd" -> "TrueHD"
    "audio/vnd.dts" -> "DTS"
    "audio/vnd.dts.hd" -> "DTS-HD"
    "audio/alac" -> "ALAC"
    "audio/amr-wb" -> "AMR-WB"

    // Subtitles.
    "application/x-subrip" -> "SubRip (SRT)"
    "text/vtt" -> "WebVTT"
    "application/ttml+xml" -> "TTML"
    "application/pgs" -> "PGS"
    "application/vobsub" -> "VobSub"
    "text/x-ssa" -> "ASS / SSA"

    // Containers.
    "video/mp4" -> "MP4"
    "audio/mp4" -> "MP4"
    "video/webm" -> "WebM"
    "audio/webm" -> "WebM"
    "video/x-matroska" -> "Matroska (MKV)"
    "audio/x-matroska" -> "Matroska (MKV)"
    "video/quicktime" -> "QuickTime (MOV)"
    "video/x-msvideo" -> "AVI"
    "video/mp2t" -> "MPEG-TS"
    "audio/mpeg-L1" -> "MPEG audio"

    else -> mimeType
}
