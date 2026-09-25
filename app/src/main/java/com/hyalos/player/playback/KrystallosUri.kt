package com.hyalos.player.playback

import android.net.Uri

/**
 * `krystallos://<serverId>/<path>` — how a remote file is named to Media3.
 *
 * Built with [Uri.Builder] so that `#`, `?`, `%` and non-ASCII names are
 * encoded, and read back with [Uri.getPath], which decodes them. Hand-built
 * strings would turn a file called `a#1.mkv` into a fragment.
 */
object KrystallosUri {
    const val SCHEME = "krystallos"

    fun of(serverId: String, path: String): Uri =
        Uri.Builder().scheme(SCHEME).authority(serverId).path(path).build()
}
