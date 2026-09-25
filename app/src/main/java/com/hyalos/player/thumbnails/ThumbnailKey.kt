package com.hyalos.player.thumbnails

import java.security.MessageDigest

/**
 * Identifies one video's thumbnail.
 *
 * **Size and modification time are part of the identity on purpose.** Keying on
 * the path alone would keep serving the old frame after the file behind it was
 * replaced — the same path, a different film, and a thumbnail that lies.
 *
 * Pure logic with no Android dependency, so the hashing can be tested on the JVM.
 */
data class ThumbnailKey(
    val serverId: String,
    val path: String,
    val size: Long?,
    val modifiedMs: Long?,
) {
    /**
     * A stable, filesystem-safe name for this key.
     *
     * Hashed because [path] carries arbitrary text — `/`, `#`, spaces, CJK —
     * none of which a file name can hold verbatim. A short digest also keeps
     * directory listings cheap to read.
     */
    val hash: String by lazy {
        val digest = MessageDigest.getInstance("SHA-256")
        // Fields are NUL-separated so that ("a", "b/c") and ("a/b", "c") cannot
        // hash the same, which plain concatenation would allow.
        fun field(value: String) {
            digest.update(value.encodeToByteArray())
            digest.update(0)
        }
        field(serverId)
        field(path)
        field(size?.toString() ?: "-")
        field(modifiedMs?.toString() ?: "-")
        digest.digest().take(HASH_BYTES).joinToString("") { "%02x".format(it) }
    }

    private companion object {
        /** 16 bytes = 32 hex characters: ample against collisions, short as a name. */
        const val HASH_BYTES = 16
    }
}
