package com.hyalos.player.data

import com.hyalos.player.data.ServerConfig.Problem
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerConfigTest {
    private fun server(
        name: String = "NAS",
        host: String = "192.168.1.10",
        share: String = "media",
        startPath: String = "/",
    ) = ServerConfig(id = "id", name = name, host = host, share = share, startPath = startPath)

    @Test
    fun `the uri is host and share, verbatim`() {
        assertEquals("smb://192.168.1.10/media", server().smbUri())
        // No percent-encoding: the kernel splits the raw string and would send
        // `%E5...` to the server literally.
        assertEquals("smb://nas.local/影片 库", server(host = "nas.local", share = "影片 库").smbUri())
    }

    @Test
    fun `a valid server has no problem`() {
        assertNull(server().validate())
    }

    @Test
    fun `host characters the kernel treats as separators are rejected`() {
        for (bad in listOf("nas/x", "dom;nas", "user@nas", "na s", "nas\\x")) {
            assertEquals(bad, Problem.HOST_INVALID, server(host = bad).validate())
        }
    }

    @Test
    fun `a share with a path in it is rejected`() {
        // The kernel would take only the first component and silently drop the
        // rest; the start path is where a subdirectory belongs.
        assertEquals(Problem.SHARE_INVALID, server(share = "media/movies").validate())
    }

    @Test
    fun `empty fields are reported in order`() {
        assertEquals(Problem.NAME_EMPTY, server(name = " ").validate())
        assertEquals(Problem.HOST_EMPTY, server(host = "").validate())
        assertEquals(Problem.SHARE_EMPTY, server(share = "").validate())
    }

    @Test
    fun `a relative start path is rejected`() {
        assertEquals(Problem.START_PATH_INVALID, server(startPath = "movies").validate())
    }

    @Test
    fun `stored json with unknown or missing fields still loads`() {
        // A file from a newer version (extra field) or an older one (missing
        // optional fields) must not make the server list unreadable.
        val json = Json { ignoreUnknownKeys = true }
        val decoded = json.decodeFromString(
            ServerList.serializer(),
            """{"servers":[{"id":"a","name":"n","host":"h","share":"s","futureField":1}]}""",
        )
        assertEquals("/", decoded.servers.single().startPath)
        assertEquals(false, decoded.servers.single().smbSeal)
    }
}
