package com.hyalos.player.kernel

import com.hyalos.player.data.CredentialStore
import com.hyalos.player.data.ServerConfig
import com.hyalos.player.data.ServerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import uniffi.krystallos_ffi.ConnectRequest
import uniffi.krystallos_ffi.DirEntry
import uniffi.krystallos_ffi.Kernel
import uniffi.krystallos_ffi.KernelException
import uniffi.krystallos_ffi.Session
import uniffi.krystallos_ffi.SmbInfo
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the connections to servers.
 *
 * # Two kinds of session
 *
 * - **Browsing** shares one cached session per server, so moving between
 *   directories does not re-authenticate each time.
 * - **Playback** gets its own, from [connectDedicated], owned by the player.
 *
 * They are kept apart because each kernel session is a single thread that runs
 * one operation at a time, with a 20-second timeout. A playback read stuck on
 * a flaky network would otherwise freeze the file browser behind it.
 *
 * # Losing the connection
 *
 * `ConnectionLost` means the session is dead and every later call on it fails
 * too, so the cached one is dropped and the next call reconnects. Operations
 * that are safe to repeat — listing, stat — are retried once on a fresh
 * session; anything else surfaces the error rather than risk doing it twice.
 */
class SessionManager(
    private val kernel: () -> Kernel,
    private val servers: ServerRepository,
    private val credentials: CredentialStore,
    private val scope: CoroutineScope,
) {
    private val sessions = ConcurrentHashMap<String, Session>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** List a directory on [serverId], reconnecting once if the session had died. */
    suspend fun list(serverId: String, path: String): List<DirEntry> =
        withBrowseSession(serverId) { it.list(path) }

    /** A session the caller owns and must [shutdown]. */
    suspend fun connectDedicated(serverId: String): Session = connect(request(server(serverId)))

    /**
     * Connect with settings that are not saved yet, list [ServerConfig.startPath],
     * and disconnect. What the edit screen's "test connection" does.
     *
     * Returns what the connection negotiated — `null` when the backend has
     * nothing to report, which is the case for a local directory.
     */
    suspend fun test(server: ServerConfig, password: String?): SmbInfo? {
        val session = connect(request(server, password))
        try {
            session.list(server.startPath)
            return session.smbInfo()
        } finally {
            session.shutdown()
        }
    }

    /**
     * Forget the cached session for [serverId]. Called when its settings change
     * or it is deleted, so the next call connects with the new ones.
     *
     * The disconnect itself is not awaited: it queues behind whatever the
     * session is doing, and the caller should not wait on that.
     */
    fun invalidate(serverId: String) {
        val session = sessions.remove(serverId) ?: return
        scope.launch { session.shutdown() }
    }

    /**
     * Run [block] on the cached session, reconnecting once if it turns out to be
     * dead.
     *
     * Retried for anything that is not an answer about the path — see
     * [describesThePath]. A listing is a read: repeating it costs one round trip
     * and is the difference between recovering on its own and showing the user a
     * retry button that would have worked.
     */
    private suspend fun <T> withBrowseSession(serverId: String, block: suspend (Session) -> T): T {
        val session = browseSession(serverId)
        return try {
            block(session)
        } catch (e: KernelException) {
            if (e.describesThePath) throw e
            // Only drop it if nobody replaced it in the meantime.
            if (sessions.remove(serverId, session)) scope.launch { session.shutdown() }
            block(browseSession(serverId))
        }
    }

    private suspend fun browseSession(serverId: String): Session =
        // One connect per server at a time: two screens asking at once would
        // otherwise both authenticate and one session would leak.
        locks.getOrPut(serverId) { Mutex() }.withLock {
            sessions[serverId]?.takeUnless { it.isClosed() }
                ?: connect(request(server(serverId))).also { sessions[serverId] = it }
        }

    private suspend fun server(serverId: String): ServerConfig =
        servers.get(serverId) ?: throw ServerNotFoundException(serverId)

    /**
     * The connect request for [server]. Never log the result: it is a data
     * class, and its `toString()` includes the password.
     */
    private suspend fun request(server: ServerConfig, password: String? = null) = ConnectRequest(
        uri = server.smbUri(),
        username = server.username?.takeIf { it.isNotBlank() },
        password = password ?: credentials.passwordFor(server.id),
        domain = server.domain?.takeIf { it.isNotBlank() },
        smbSeal = server.smbSeal,
    )

    /**
     * Off the main thread: constructing the kernel loads a native library, and
     * a connect can block briefly on the Rust side before it suspends.
     */
    private suspend fun connect(request: ConnectRequest): Session =
        withContext(Dispatchers.IO) { kernel().connect(request) }
}

/** The server was deleted while something still referred to it. */
class ServerNotFoundException(val serverId: String) : Exception("server $serverId no longer exists")
