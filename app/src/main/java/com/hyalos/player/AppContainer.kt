package com.hyalos.player

import android.content.Context
import com.hyalos.player.data.CredentialStore
import com.hyalos.player.data.ServerRepository
import com.hyalos.player.data.SettingsRepository
import com.hyalos.player.kernel.SessionManager
import com.hyalos.player.thumbnails.ExtractLane
import com.hyalos.player.thumbnails.LanePool
import com.hyalos.player.thumbnails.ThumbnailCache
import com.hyalos.player.thumbnails.ThumbnailLoader
import com.hyalos.player.thumbnails.ThumbnailSources
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import uniffi.krystallos_ffi.Kernel
import java.io.File

/**
 * Process-wide objects, created once in [HyalosApp].
 *
 * Everything here outlives any screen, which is the point: sessions and
 * credentials must not be re-created on rotation or navigation.
 */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext

    /**
     * For work that must finish even after the screen that started it is gone —
     * disconnecting a session when a player closes, for one.
     */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Lazy because constructing it loads the Rust library through JNA, which
     * is not free and need not happen before the first screen draws.
     */
    val kernel: Kernel by lazy { Kernel() }

    val servers = ServerRepository.create(appContext)
    val credentials = CredentialStore.create(appContext)
    val settings = SettingsRepository.create(appContext)
    val sessions = SessionManager({ kernel }, servers, credentials, appScope)

    private val thumbnailCache = ThumbnailCache(File(appContext.cacheDir, THUMBNAIL_DIR))

    private val thumbnailSources = ThumbnailSources(
        connect = { serverId -> sessions.connectDedicated(serverId) },
        scope = appScope,
    )

    private val thumbnailLanes = LanePool(
        lanes = List(THUMBNAIL_LANES) { ExtractLane(appContext, thumbnailSources, it) },
        sources = thumbnailSources,
    )

    val thumbnails = ThumbnailLoader(thumbnailCache, thumbnailLanes)

    init {
        // The limit is a setting; the cache is what acts on it. Collected here
        // rather than read once, so changing it takes effect without a restart.
        appScope.launch {
            settings.settings.collect { current ->
                thumbnailCache.setLimit(current.thumbnailCacheMb.toLong() * BYTES_PER_MB)
            }
        }
    }

    /**
     * Drop everything tied to one server: its browsing session, its thumbnails'
     * session. For when its settings change or it is deleted.
     */
    fun forgetServer(serverId: String) {
        sessions.invalidate(serverId)
        thumbnailSources.invalidate(serverId)
    }

    private companion object {
        const val THUMBNAIL_DIR = "thumbnails"

        /**
         * Two, so a directory fills in about twice as fast without putting four
         * simultaneous decoders and SMB sessions on a NAS. Each lane is a thread
         * and a connection of its own.
         */
        const val THUMBNAIL_LANES = 2

        const val BYTES_PER_MB = 1024L * 1024L
    }
}
