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
