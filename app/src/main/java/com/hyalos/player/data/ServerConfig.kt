package com.hyalos.player.data

import kotlinx.serialization.Serializable

/**
 * A saved SMB server.
 *
 * The password is deliberately not here: this is stored as plain JSON and may
 * be backed up, while the password lives encrypted in [CredentialStore], keyed
 * by [id].
 *
 * @property startPath where browsing starts. The kernel's session root is
 *   always the share root — anything after the share in an `smb://` URI is
 *   ignored — so a starting directory has to be an app-side concept.
 */
@Serializable
data class ServerConfig(
    val id: String,
    val name: String,
    val host: String,
    val share: String,
    val startPath: String = "/",
    val username: String? = null,
    val domain: String? = null,
    val smbSeal: Boolean = false,
    /**
     * TCP port, or `null` for the protocol default (445). Only worth setting
     * for a server that was deliberately moved off 445 — a NAS behind a port
     * forward, or one host carrying several SMB servers.
     */
    val port: Int? = null,
) {
    /**
     * The endpoint the kernel connects to.
     *
     * Deliberately **not** percent-encoded: the kernel splits the raw string on
     * `/`, `;` and `@` without decoding (`ParsedEndpoint::parse` in
     * krystallos-smb), so encoding here would send `%20` to the server as a
     * literal. [validate] rejects the characters that would break the split.
     *
     * The port is appended rather than passed separately because libsmb2 has no
     * other way to receive one: it reads `host:port` out of the server string
     * itself (`lib/socket.c`, `smb2_connect_async`), defaulting to 445. The
     * kernel passes the authority through untouched, so this reaches it intact.
     *
     * User and domain are not embedded; they travel as separate fields of the
     * connect request, which keeps them out of anything that logs a URI.
     */
    fun smbUri(): String = "smb://${authority()}/$share"

    /**
     * `host`, `host:port`, `[v6]` or `[v6]:port`.
     *
     * IPv6 must be bracketed: libsmb2 only treats an address as IPv6 when it
     * starts with `[`, and otherwise reads everything after the first colon as
     * the port — so a bare literal would be cut in half and fail to resolve.
     */
    private fun authority(): String {
        val host = if (host.contains(':') && !(host.startsWith("[") && host.endsWith("]"))) {
            "[$host]"
        } else {
            host
        }
        return if (port == null) host else "$host:$port"
    }

    /** What is wrong with this configuration, or `null` if nothing is. */
    fun validate(): Problem? = when {
        name.isBlank() -> Problem.NAME_EMPTY
        host.isBlank() -> Problem.HOST_EMPTY
        host.any { it in HOST_FORBIDDEN || it.isWhitespace() } -> Problem.HOST_INVALID
        // One colon is `host:port` typed into the wrong field; two or more is an
        // IPv6 literal. Saying so beats bracketing it and failing to resolve.
        host.count { it == ':' } == 1 -> Problem.PORT_IN_HOST
        host.startsWith("[") != host.endsWith("]") -> Problem.HOST_INVALID
        share.isBlank() -> Problem.SHARE_EMPTY
        '/' in share || '\\' in share -> Problem.SHARE_INVALID
        !startPath.startsWith("/") -> Problem.START_PATH_INVALID
        port != null && port !in 1..65535 -> Problem.PORT_INVALID
        else -> null
    }

    enum class Problem {
        NAME_EMPTY,
        HOST_EMPTY,
        HOST_INVALID,
        PORT_IN_HOST,
        SHARE_EMPTY,
        SHARE_INVALID,
        START_PATH_INVALID,
        PORT_INVALID,
    }

    private companion object {
        /** Characters the kernel's endpoint parser treats as separators. */
        val HOST_FORBIDDEN = setOf('/', ';', '@', '\\')
    }
}

/** The stored list, wrapped so the JSON document can grow fields later. */
@Serializable
data class ServerList(val servers: List<ServerConfig> = emptyList())
