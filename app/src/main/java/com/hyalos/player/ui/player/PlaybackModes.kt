package com.hyalos.player.ui.player

import androidx.annotation.DrawableRes
import androidx.media3.common.Player
import com.hyalos.player.R
import com.hyalos.player.data.PlaybackMode

/**
 * The four playback modes, as the two things the player actually has to be told.
 *
 * Four modes sound like four behaviours, and they are — but every one of them
 * is answered by two questions: **is the queue the folder or just this film**,
 * and **does the end of the queue wrap or stop**. Writing them out as a `when`
 * in the ViewModel would have hidden that; here it is the whole file.
 *
 * Pure, and deliberately not in `data/`: [PlaybackMode] is a stored value and
 * has no business knowing what a `repeatMode` is. This is the player's
 * vocabulary, so it lives with the player, and it can be tested without an
 * `ExoPlayer` anywhere near it.
 */

/** What `player.repeatMode` has to be for this mode. */
internal val PlaybackMode.repeatMode: Int
    get() = when (this) {
        // The queue ends and stays ended.
        PlaybackMode.SEQUENCE -> Player.REPEAT_MODE_OFF
        PlaybackMode.REPEAT_ALL -> Player.REPEAT_MODE_ALL
        PlaybackMode.REPEAT_ONE -> Player.REPEAT_MODE_ONE

        // Off, and it must be set to off rather than left alone: coming here
        // from REPEAT_ONE with a queue of one would otherwise repeat forever
        // under a button that says it will not.
        PlaybackMode.ONCE -> Player.REPEAT_MODE_OFF
    }

/**
 * Whether the queue should hold the whole folder, or only the film playing.
 *
 * [PlaybackMode.ONCE] is the only one that wants the neighbours gone. It is
 * also what makes Media3 grey out its own next/previous buttons, which is the
 * honest thing for the controller to show: there is nothing to go to.
 */
internal val PlaybackMode.wantsFullQueue: Boolean
    get() = this != PlaybackMode.ONCE

/**
 * The mode the controller's button moves to next.
 *
 * Cycled in declaration order, which is the order they are listed in — so the
 * two that play the folder are adjacent, and the two that play one film are
 * adjacent, and no mode is ever more than three taps away.
 */
/**
 * The controller button's icon for this mode.
 *
 * One button showing four states, so the icon is the whole of what the button
 * says. These live here rather than in a `when` in the screen for the same
 * reason as the rest of this file: the mapping is the interesting part, and
 * here it can be read in one place.
 */
@get:DrawableRes
internal val PlaybackMode.iconRes: Int
    get() = when (this) {
        PlaybackMode.SEQUENCE -> R.drawable.ic_mode_sequence
        PlaybackMode.REPEAT_ALL -> R.drawable.ic_mode_repeat_all
        PlaybackMode.REPEAT_ONE -> R.drawable.ic_mode_repeat_one
        PlaybackMode.ONCE -> R.drawable.ic_mode_once
    }

internal val PlaybackMode.next: PlaybackMode
    get() = PlaybackMode.values().let { it[(ordinal + 1) % it.size] }
