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
) {
    /**
     * The endpoint the kernel connects to.
     *
     * Deliberately **not** percent-encoded: the kernel splits the raw string on
     * `/`, `;` and `@` without decoding (`ParsedEndpoint::parse` in
     * krystallos-smb), so encoding here would send `%20` to the server as a
     * literal. [validate] rejects the characters that would break the split.
     *
     * User and domain are not embedded either; they travel as separate fields
     * of the connect request, which keeps them out of anything that logs a URI.
     */
    fun smbUri(): String = "smb://$host/$share"

    /** What is wrong with this configuration, or `null` if nothing is. */
    fun validate(): Problem? = when {
        name.isBlank() -> Problem.NAME_EMPTY
        host.isBlank() -> Problem.HOST_EMPTY
        host.any { it in HOST_FORBIDDEN || it.isWhitespace() } -> Problem.HOST_INVALID
        share.isBlank() -> Problem.SHARE_EMPTY
        '/' in share || '\\' in share -> Problem.SHARE_INVALID
        !startPath.startsWith("/") -> Problem.START_PATH_INVALID
        else -> null
    }

    enum class Problem {
        NAME_EMPTY,
        HOST_EMPTY,
        HOST_INVALID,
        SHARE_EMPTY,
        SHARE_INVALID,
        START_PATH_INVALID,
    }

    private companion object {
        /** Characters the kernel's endpoint parser treats as separators. */
        val HOST_FORBIDDEN = setOf('/', ';', '@', '\\')
    }
}

/** The stored list, wrapped so the JSON document can grow fields later. */
@Serializable
data class ServerList(val servers: List<ServerConfig> = emptyList())
