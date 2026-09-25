package com.hyalos.player.ui

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * Every screen, as a Navigation 3 key.
 *
 * `@Serializable` because `rememberNavBackStack` saves the back stack through
 * process death, and it does so by serialising the keys. Keep them to plain
 * values — ids and paths, never objects — so a restored stack reloads its data
 * rather than resurrecting stale copies.
 */
sealed interface Route : NavKey {
    @Serializable
    data object Servers : Route

    @Serializable
    data object Settings : Route

    /**
     * The pages under [Settings]. Each is a plain entry on the same stack rather
     * than a nested graph: there is one level of depth, and pushing is the same
     * `backStack.add` every other screen uses.
     */
    @Serializable
    data object SettingsPlayback : Route

    @Serializable
    data object SettingsStorage : Route

    @Serializable
    data object SettingsAbout : Route

    /** `serverId == null` adds a new server. */
    @Serializable
    data class EditServer(val serverId: String? = null) : Route

    /** One directory. Each level of browsing is its own entry on the stack. */
    @Serializable
    data class Browse(val serverId: String, val path: String) : Route

    /**
     * `fromPlaylist` says where the queue comes from: `false` is the folder the
     * file sits in, `true` is the server's own playlist.
     *
     * Defaulted rather than required so a back stack saved by an earlier version
     * — which has no such field — restores straight into folder mode.
     */
    @Serializable
    data class Play(
        val serverId: String,
        val path: String,
        val fromPlaylist: Boolean = false,
    ) : Route

    /** One server's playlist. The list belongs to a server, so the id is the key. */
    @Serializable
    data class Playlist(val serverId: String) : Route
}
