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

    /** `serverId == null` adds a new server. */
    @Serializable
    data class EditServer(val serverId: String? = null) : Route

    /** One directory. Each level of browsing is its own entry on the stack. */
    @Serializable
    data class Browse(val serverId: String, val path: String) : Route

    @Serializable
    data class Play(val serverId: String, val path: String) : Route
}
