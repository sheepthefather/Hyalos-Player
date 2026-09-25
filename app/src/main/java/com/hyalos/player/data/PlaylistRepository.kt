package com.hyalos.player.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

/**
 * One playlist per server, persisted as `playlists.json`.
 *
 * **Per server, not one list across all of them**, because a player holds a
 * single session: `ReaderSource.reader` takes only a path, and
 * `KrystallosDataSource` never reads the `serverId` out of the URI. An entry
 * from another server would be looked up on the current one — and where that
 * path happens to exist there, the wrong film plays without any error at all.
 *
 * The entries are plain paths. The file name and its folder are both derived
 * from the path (`RemotePath.name` / `RemotePath.parent`) rather than stored: a
 * stored name would go on saying "Episode 3" after the file had been renamed,
 * while the path beside it pointed at something that no longer exists.
 *
 * This file is allowed into backups, like the server list — it holds no secrets.
 */
class PlaylistRepository(private val store: DataStore<Playlists>) {

    fun playlist(serverId: String): Flow<List<String>> =
        store.data.map { it.byServer[serverId].orEmpty() }

    /**
     * Every playlist at once, for the one screen that picks a server rather than
     * showing entries — it has to know which of them have anything in them.
     */
    val all: Flow<Map<String, List<String>>> = store.data.map { it.byServer }

    /**
     * Append [paths] to [serverId]'s playlist, skipping the ones already in it,
     * and report how many were new.
     *
     * The count is returned rather than assumed to be `paths.size`, so the caller
     * can say "added 3, 2 were already there" instead of claiming all five — the
     * same reason file operations report partial success.
     */
    suspend fun add(serverId: String, paths: List<String>): Int {
        // Written by the transform below. Assigning a value derived purely from
        // the transform's own input keeps it a pure function of that input, which
        // is what DataStore asks of the lambda: a retry computes the same number.
        var added = 0
        store.updateData { all ->
            val result = appended(all.byServer[serverId].orEmpty(), paths)
            added = result.added
            all.copy(byServer = all.byServer + (serverId to result.paths))
        }
        return added
    }

    /** Remove [paths] in one update — one write for a whole selection, not one each. */
    suspend fun removeAll(serverId: String, paths: List<String>) {
        if (paths.isEmpty()) return
        val doomed = paths.toSet()
        store.updateData { all ->
            val existing = all.byServer[serverId] ?: return@updateData all
            all.copy(byServer = all.byServer + (serverId to existing.filterNot { it in doomed }))
        }
    }

    suspend fun clear(serverId: String) {
        store.updateData { all ->
            if (serverId !in all.byServer) return@updateData all
            all.copy(byServer = all.byServer - serverId)
        }
    }

    /** Drop a deleted server's playlist. */
    suspend fun forget(serverId: String) = clear(serverId)

    companion object {
        fun create(context: Context): PlaylistRepository = PlaylistRepository(
            DataStoreFactory.create(
                serializer = JsonSerializer(Playlists.serializer(), Playlists()),
                // Same trade as the server list: an unreadable file that kept
                // failing every read would leave the app unusable, which is worse
                // than losing the lists.
                corruptionHandler = ReplaceFileCorruptionHandler { Playlists() },
                produceFile = { context.dataStoreFile(FILE_NAME) },
            ),
        )

        /** Referenced by the backup rules in `res/xml`, so keep them in step. */
        const val FILE_NAME = "playlists.json"
    }
}

/**
 * The playlists, keyed by server id.
 *
 * A document of its own rather than a bare map, for the reason [ServerList] is:
 * the file can gain fields later without a migration.
 */
@Serializable
data class Playlists(val byServer: Map<String, List<String>> = emptyMap())

/** What [appended] came to: the new list, and how much of it was new. */
internal data class Appended(val paths: List<String>, val added: Int)

/**
 * [existing] followed by the entries of [added] it does not already have.
 *
 * Keeps the order, and de-duplicates within [added] too — selecting the same
 * film twice in one gesture should not put it in the list twice.
 */
internal fun appended(existing: List<String>, added: List<String>): Appended {
    val seen = existing.toMutableSet()
    val merged = existing.toMutableList()
    var count = 0
    for (path in added) {
        if (seen.add(path)) {
            merged += path
            count++
        }
    }
    return Appended(merged, count)
}
