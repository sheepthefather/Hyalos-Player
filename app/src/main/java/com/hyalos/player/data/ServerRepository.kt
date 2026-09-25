package com.hyalos.player.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The saved servers, persisted as `servers.json`.
 *
 * This file is allowed into backups — restoring to a new phone brings the
 * server list back, and only the passwords (which live in [CredentialStore]
 * and are excluded) need entering again.
 */
class ServerRepository(private val store: DataStore<ServerList>) {

    val servers: Flow<List<ServerConfig>> = store.data.map { it.servers }

    suspend fun get(id: String): ServerConfig? = servers.first().find { it.id == id }

    /** Add [server], or replace the one with the same id in place. */
    suspend fun upsert(server: ServerConfig) {
        store.updateData { list ->
            val index = list.servers.indexOfFirst { it.id == server.id }
            val updated = if (index < 0) {
                list.servers + server
            } else {
                list.servers.toMutableList().also { it[index] = server }
            }
            list.copy(servers = updated)
        }
    }

    suspend fun delete(id: String) {
        store.updateData { list -> list.copy(servers = list.servers.filterNot { it.id == id }) }
    }

    companion object {
        fun create(context: Context): ServerRepository = ServerRepository(
            DataStoreFactory.create(
                serializer = JsonSerializer(ServerList.serializer(), ServerList()),
                // An unreadable file would otherwise fail every read and leave
                // the app unable to start. Losing the list is bad; being unable
                // to add a server again is worse.
                corruptionHandler = ReplaceFileCorruptionHandler { ServerList() },
                produceFile = { context.dataStoreFile(FILE_NAME) },
            ),
        )

        /** Referenced by the backup rules in `res/xml`, so keep them in step. */
        const val FILE_NAME = "servers.json"
    }
}
