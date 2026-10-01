package com.hyalos.player.ui.settings

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.hyalos.player.R
import com.hyalos.player.data.PlaybackMode
import com.hyalos.player.data.PlaybackOrientation
import com.hyalos.player.data.VideoScale

/**
 * Value-to-text mappings shared by the settings index and the pages under it.
 *
 * The index reports the very numbers its sub-pages edit, so the wording has to
 * come from one place: "关闭" in the index against "0 MB" on the storage page
 * would read as two different settings.
 */

/** In the order they are shown; `FIT` first because it is the default. */
internal val SCALES = listOf(
    VideoScale.FIT to R.string.scale_fit,
    VideoScale.FILL to R.string.scale_fill,
    VideoScale.ZOOM to R.string.scale_zoom,
)

@StringRes
internal fun scaleLabel(scale: VideoScale): Int =
    SCALES.first { (value, _) -> value == scale }.second

/** In the order they are shown; `LANDSCAPE` first because it is the default. */
internal val ORIENTATIONS = listOf(
    PlaybackOrientation.LANDSCAPE to R.string.orientation_landscape,
    PlaybackOrientation.PORTRAIT to R.string.orientation_portrait,
)

@StringRes
internal fun orientationLabel(orientation: PlaybackOrientation): Int =
    ORIENTATIONS.first { (value, _) -> value == orientation }.second

/**
 * In the order they are shown, which is [PlaybackMode]'s own order — the order
 * the player's button cycles in, so the list here and the button there agree.
 *
 * Here rather than only in the player because the settings index reports the
 * mode it is currently on, and that has to read exactly as the player names it.
 */
internal val PLAYBACK_MODES = listOf(
    PlaybackMode.SEQUENCE to R.string.mode_sequence,
    PlaybackMode.REPEAT_ALL to R.string.mode_repeat_all,
    PlaybackMode.REPEAT_ONE to R.string.mode_repeat_one,
    PlaybackMode.ONCE to R.string.mode_once,
)

@StringRes
internal fun playbackModeLabel(mode: PlaybackMode): Int =
    PLAYBACK_MODES.first { (value, _) -> value == mode }.second

@Composable
internal fun cacheLimitLabel(megabytes: Int): String =
    if (megabytes == 0) stringResource(R.string.settings_cache_off) else "$megabytes MB"
