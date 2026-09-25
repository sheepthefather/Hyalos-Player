package com.hyalos.player

import android.content.Context
import com.hyalos.player.data.CredentialStore
import com.hyalos.player.data.ServerRepository
import com.hyalos.player.kernel.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import uniffi.krystallos_ffi.Kernel

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
    val sessions = SessionManager({ kernel }, servers, credentials, appScope)
}
