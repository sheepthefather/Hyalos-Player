package com.hyalos.player.kernel

/**
 * Paths inside a session, as the kernel expects them: `/`-separated and
 * rooted at the share.
 *
 * Names are joined **verbatim**. The kernel's own rule is to address an entry by
 * the name the server returned; rebuilding it — normalising Unicode, trimming,
 * escaping — breaks lookups on servers that store a different normalisation
 * form, which macOS shares do.
 */
object RemotePath {
    const val ROOT = "/"

    /** Collapse repeated separators, drop a trailing one, ensure a leading one. */
    fun normalize(path: String): String {
        val segments = segments(path)
        return if (segments.isEmpty()) ROOT else segments.joinToString("/", prefix = "/")
    }

    fun segments(path: String): List<String> = path.split('/').filter { it.isNotEmpty() }

    fun join(dir: String, name: String): String {
        val base = normalize(dir)
        return if (base == ROOT) "/$name" else "$base/$name"
    }

    /** The containing directory, or `null` for the root. */
    fun parent(path: String): String? {
        val segments = segments(path)
        if (segments.isEmpty()) return null
        return if (segments.size == 1) ROOT else segments.dropLast(1).joinToString("/", prefix = "/")
    }

    /** The last component, or an empty string for the root. */
    fun name(path: String): String = segments(path).lastOrNull().orEmpty()

    /** Every directory from the root down to [path] itself: `/`, `/a`, `/a/b`. */
    fun ancestors(path: String): List<String> {
        val segments = segments(path)
        return listOf(ROOT) + segments.indices.map { i ->
            segments.take(i + 1).joinToString("/", prefix = "/")
        }
    }
}
