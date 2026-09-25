package com.hyalos.player

import android.app.Application

/**
 * Holds the [AppContainer] — the app's hand-written dependency graph.
 *
 * Manual rather than Hilt: the graph is a handful of objects, and Hilt needs
 * KSP, whose interaction with AGP 9's built-in Kotlin is one more thing to go
 * wrong in a build that already has a Rust toolchain in it.
 */
class HyalosApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
