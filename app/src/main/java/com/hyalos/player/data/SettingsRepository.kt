package com.hyalos.player.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import kotlinx.coroutines.flow.Flow

/** App preferences, persisted as `settings.json`. Allowed into backups. */
class SettingsRepository(private val store: DataStore<AppSettings>) {

    val settings: Flow<AppSettings> = store.data

    suspend fun setThumbnailCacheMb(megabytes: Int) {
        store.updateData { it.copy(thumbnailCacheMb = megabytes) }
    }

    suspend fun setBrowserLayout(layout: BrowserLayout) {
        store.updateData { it.copy(browserLayout = layout) }
    }

    companion object {
        fun create(context: Context): SettingsRepository = SettingsRepository(
            DataStoreFactory.create(
                serializer = JsonSerializer(AppSettings.serializer(), AppSettings()),
                corruptionHandler = ReplaceFileCorruptionHandler { AppSettings() },
                produceFile = { context.dataStoreFile(FILE_NAME) },
            ),
        )

        const val FILE_NAME = "settings.json"
    }
}
