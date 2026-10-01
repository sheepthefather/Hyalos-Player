package com.hyalos.player.data

import kotlinx.serialization.Serializable

/**
 * App-wide preferences, stored as JSON beside the server list and allowed into
 * backups — they are settings, not secrets.
 */
@Serializable
data class AppSettings(
    /**
     * How much disk the thumbnail cache may use, in mebibytes.
     *
     * **Zero means thumbnails are switched off**, and empties whatever is
     * cached: a budget of nothing is exactly that, and the settings screen says
     * so on the slider rather than leaving it to be discovered.
     *
     * The default is far more than most libraries need. A 320×180 thumbnail
     * encodes to roughly 10–20 KB, so 100 MB holds something like seven to ten
     * thousand of them.
     */
    val thumbnailCacheMb: Int = DEFAULT_THUMBNAIL_CACHE_MB,

    /**
     * How the file browser lays out a directory.
     *
     * Global rather than per-server: it is a preference about how someone likes
     * to browse, not a property of any one NAS.
     */
    val browserLayout: BrowserLayout = BrowserLayout.LIST,

    /** What the browser orders a directory by, and which direction. */
    val sortKey: SortKey = SortKey.NAME,
    val sortAscending: Boolean = true,

    /**
     * What the player does when a film ends.
     *
     * Replaces a boolean "auto play next". That boolean's two states were
     * "play the folder through" and "stop after this one" — now [PlaybackMode.SEQUENCE]
     * and [PlaybackMode.ONCE] — and two more were missing entirely.
     *
     * It lives here to be remembered, not to be changed here: it is chosen from
     * the player's own controls, because it is a decision made while watching.
     */
    val playbackMode: PlaybackMode = PlaybackMode.SEQUENCE,

    /** How video is fitted to the screen. */
    val videoScale: VideoScale = VideoScale.FIT,

    /**
     * Which way up the player starts.
     *
     * Landscape by default, which is what most films are and what the player did
     * unconditionally before this was a setting. Portrait is for the case that
     * used to be handled by looking at the video's shape, and now is not: a film
     * shot upright opens sideways unless either this is set or the rotate button
     * on the controller is tapped.
     */
    val initialOrientation: PlaybackOrientation = PlaybackOrientation.LANDSCAPE,
) {
    companion object {
        const val DEFAULT_THUMBNAIL_CACHE_MB = 100

        /** Top of the slider. Larger values are typed into the custom field. */
        const val SLIDER_MAX_MB = 1000
    }
}

/** A directory as rows of text, or as tiles of thumbnails. */
@Serializable
enum class BrowserLayout { LIST, GRID }

/** What the file browser orders entries by. */
@Serializable
enum class SortKey { NAME, DATE, SIZE, TYPE }

/**
 * What the player does when a film ends.
 *
 * Four behaviours, but two decisions: whether the queue is the folder or just
 * this film, and whether the end of the queue wraps or stops. The mapping onto
 * those two is in the player package, where the player's own vocabulary lives —
 * this side stays a plain value that can be stored and read back.
 *
 * **The order is meaningful**: it is the order the controller's button cycles
 * in, and the order they are listed in.
 *
 * **Member names are storage.** `encodeDefaults` writes them out and the
 * settings file is read back by name, so renaming one silently resets it for
 * everyone who had it.
 */
@Serializable
enum class PlaybackMode {
    /** The folder, played through, then stopped. */
    SEQUENCE,

    /** The folder, played through, then started again. */
    REPEAT_ALL,

    /** This one film, again and again. */
    REPEAT_ONE,

    /** This one film, then stopped. */
    ONCE,
}

/**
 * How video is fitted to the screen.
 *
 * [FIT] is the default because it is the only one that neither distorts the
 * picture nor hides part of it — the other two are choices a user makes
 * deliberately, not something to be surprised by on opening a film.
 */
@Serializable
enum class VideoScale {
    /** Whole frame visible, bars where the aspect ratios differ. */
    FIT,

    /** Stretched to fill, ignoring the aspect ratio. */
    FILL,

    /** Fills the screen keeping the aspect ratio, cropping the overflow. */
    ZOOM,
}

/**
 * Which way up the player opens, and — until the rotate button is tapped —
 * stays.
 *
 * Only two, deliberately: there is no "follow the video" case any more. The
 * player used to turn itself landscape whenever the video was wider than tall,
 * which made a setting about orientation either redundant or contradictory
 * depending on the film. Now the setting is the answer, and the button on the
 * controller is how a film that disagrees with it gets played the other way.
 */
@Serializable
enum class PlaybackOrientation {
    /** The phone on its side. What most films are. */
    LANDSCAPE,

    /** The phone upright. For a film shot that way. */
    PORTRAIT,
}
