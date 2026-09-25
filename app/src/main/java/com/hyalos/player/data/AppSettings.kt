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
     * Whether finishing one film starts the next one in the folder.
     *
     * On by default: watching a series folder episode by episode is the case
     * this exists for, and a player that stopped after each one would read as
     * broken rather than as a default.
     */
    val autoPlayNext: Boolean = true,

    /** How video is fitted to the screen. */
    val videoScale: VideoScale = VideoScale.FIT,
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
