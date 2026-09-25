package com.hyalos.player.ui.serveredit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hyalos.player.AppContainer
import com.hyalos.player.data.ServerConfig
import com.hyalos.player.kernel.RemotePath
import com.hyalos.player.ui.common.UiError
import com.hyalos.player.ui.common.toUiError
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID

class ServerEditViewModel(
    private val container: AppContainer,
    private val serverId: String?,
) : ViewModel() {

    data class Form(
        val name: String = "",
        val host: String = "",
        /** Blank means the protocol default. Held as text so "abc" is not read as "no port". */
        val port: String = "",
        val share: String = "",
        val startPath: String = "/",
        val username: String = "",
        /** Blank means "keep the saved one" when editing, "none" when adding. */
        val password: String = "",
        val domain: String = "",
        val smbSeal: Boolean = false,
    )

    sealed interface TestState {
        data object Idle : TestState
        data object Running : TestState
        data object Ok : TestState
        data class Failed(val error: UiError) : TestState
    }

    val isNew = serverId == null

    var form by mutableStateOf(Form())
        private set

    /** `false` while an existing server is still being read. */
    var loaded by mutableStateOf(isNew)
        private set

    /** Shown only after a save or test attempt, not while the user is still typing. */
    var problem by mutableStateOf<ServerConfig.Problem?>(null)
        private set

    var test by mutableStateOf<TestState>(TestState.Idle)
        private set

    /** Set once saved; the screen navigates away when it sees it. */
    var saved by mutableStateOf(false)
        private set

    private var testJob: Job? = null

    init {
        if (serverId != null) {
            viewModelScope.launch {
                container.servers.get(serverId)?.let { s ->
                    form = Form(
                        name = s.name,
                        host = s.host,
                        port = s.port?.toString().orEmpty(),
                        share = s.share,
                        startPath = s.startPath,
                        username = s.username.orEmpty(),
                        domain = s.domain.orEmpty(),
                        smbSeal = s.smbSeal,
                    )
                }
                loaded = true
            }
        }
    }

    fun update(transform: (Form) -> Form) {
        form = transform(form)
        // Stale results would describe settings that are no longer on screen.
        if (problem != null) problem = currentProblem()
        if (test !is TestState.Running) test = TestState.Idle
    }

    /**
     * Connect with what is on screen, without saving it.
     *
     * Cancelling only stops the wait: the kernel does not see Kotlin
     * cancellation, so a connect in flight runs to its own timeout.
     */
    fun testConnection() {
        val config = validated() ?: return
        testJob?.cancel()
        test = TestState.Running
        testJob = viewModelScope.launch {
            test = try {
                container.sessions.test(config, form.password.ifEmpty { null } ?: savedPassword())
                TestState.Ok
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                TestState.Failed(e.toUiError())
            }
        }
    }

    fun cancelTest() {
        testJob?.cancel()
        test = TestState.Idle
    }

    fun save() {
        val config = validated() ?: return
        viewModelScope.launch {
            container.servers.upsert(config)
            if (form.password.isNotEmpty()) container.credentials.setPassword(config.id, form.password)
            // The cached session was opened with the old settings.
            container.forgetServer(config.id)
            saved = true
        }
    }

    private suspend fun savedPassword(): String? =
        serverId?.let { container.credentials.passwordFor(it) }

    private fun validated(): ServerConfig? {
        val config = config()
        problem = currentProblem()
        return config.takeIf { problem == null }
    }

    /**
     * The port is text in the form, so "abc" has to be caught here — it would
     * otherwise parse to `null`, which means "use the default port", and the
     * mistake would pass silently.
     */
    private fun currentProblem(): ServerConfig.Problem? =
        if (form.port.isNotBlank() && form.port.trim().toIntOrNull() == null) {
            ServerConfig.Problem.PORT_INVALID
        } else {
            config().validate()
        }

    private fun config() = ServerConfig(
        id = serverId ?: newId,
        name = form.name.trim(),
        host = form.host.trim(),
        share = form.share.trim(),
        startPath = RemotePath.normalize(form.startPath.trim()),
        username = form.username.trim().ifEmpty { null },
        domain = form.domain.trim().ifEmpty { null },
        smbSeal = form.smbSeal,
        port = form.port.trim().toIntOrNull(),
    )

    /** Stable across test and save, so a new server keeps one id. */
    private val newId by lazy { UUID.randomUUID().toString() }
}
