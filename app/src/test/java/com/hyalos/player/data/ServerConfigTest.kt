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
        port: Int? = null,
    ) = ServerConfig(
        id = "id",
        name = name,
        host = host,
        share = share,
        startPath = startPath,
        port = port,
    )

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
    fun `a port goes after the host, and its absence leaves the uri alone`() {
        // libsmb2 reads the port out of the server string and defaults to 445,
        // so no port must mean no colon at all.
        assertEquals("smb://192.168.1.10/media", server().smbUri())
        assertEquals("smb://192.168.1.10:1445/media", server(port = 1445).smbUri())
        assertNull(server(port = 1445).validate())
    }

    @Test
    fun `a port keeps its own field rather than moving into the host`() {
        // Constructed the other way round this would be `host:port` in `host`,
        // which libsmb2 would split itself — the two paths must agree.
        assertEquals(server(host = "nas.local", port = 445).smbUri(), "smb://nas.local:445/media")
    }

    @Test
    fun `an ipv6 literal is bracketed, with or without a port`() {
        // Bare, libsmb2 would read everything after the first colon as the port
        // and try to resolve "fe80" — so the brackets are not cosmetic.
        assertEquals("smb://[fe80::1]/media", server(host = "fe80::1").smbUri())
        assertEquals("smb://[fe80::1]:1445/media", server(host = "fe80::1", port = 1445).smbUri())
        assertNull(server(host = "fe80::1", port = 1445).validate())
    }

    @Test
    fun `brackets the user typed are not doubled`() {
        assertEquals("smb://[fe80::1]/media", server(host = "[fe80::1]").smbUri())
        assertEquals("smb://[fe80::1]:1445/media", server(host = "[fe80::1]", port = 1445).smbUri())
    }

    @Test
    fun `an unbalanced bracket is rejected`() {
        assertEquals(Problem.HOST_INVALID, server(host = "[fe80::1").validate())
        assertEquals(Problem.HOST_INVALID, server(host = "fe80::1]").validate())
    }

    @Test
    fun `a port typed into the host field is named rather than guessed at`() {
        // One colon is `host:port`; two or more is an IPv6 literal. Bracketing
        // this instead would send it to getaddrinfo as an IPv6 address.
        assertEquals(Problem.PORT_IN_HOST, server(host = "192.168.1.10:1445").validate())
    }

    @Test
    fun `a port outside 1-65535 is rejected at both ends`() {
        for (bad in listOf(0, -1, 65536)) {
            assertEquals("port $bad", Problem.PORT_INVALID, server(port = bad).validate())
        }
        assertNull(server(port = 1).validate())
        assertNull(server(port = 65535).validate())
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
