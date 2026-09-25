package com.hyalos.player.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class BackStackOpsTest {
    private fun browse(path: String, server: String = "s") = Route.Browse(server, path)

    @Test
    fun `jumping to a directory already on the stack pops back to it`() {
        val stack = listOf(Route.Servers, browse("/"), browse("/a"), browse("/a/b"))
        assertEquals(listOf(Route.Servers, browse("/")), jumpTo(stack, browse("/")))
    }

    @Test
    fun `jumping above the start path replaces this server's directories`() {
        // Browsing began at /movies/2024, so /movies was never on the stack.
        val stack = listOf(Route.Servers, browse("/movies/2024"), browse("/movies/2024/x"))
        assertEquals(listOf(Route.Servers, browse("/movies")), jumpTo(stack, browse("/movies")))
    }

    @Test
    fun `entries below another server's directories are left alone`() {
        val stack = listOf(Route.Servers, browse("/", server = "other"), browse("/deep"))
        assertEquals(
            listOf(Route.Servers, browse("/", server = "other"), browse("/")),
            jumpTo(stack, browse("/")),
        )
    }

    @Test
    fun `replaceWith keeps the common prefix untouched`() {
        val a = browse("/")
        val stack = mutableListOf<Route>(Route.Servers, a, browse("/x"), browse("/x/y"))
        stack.replaceWith(listOf(Route.Servers, a, browse("/z")))
        assertEquals(listOf(Route.Servers, a, browse("/z")), stack)
        // The same instance, not an equal copy — its entry state survives.
        assert(stack[1] === a)
    }

    @Test
    fun `replaceWith accepts a result computed from the same list`() {
        val stack = mutableListOf<Route>(Route.Servers, browse("/"), browse("/a"))
        stack.replaceWith(jumpTo(stack, browse("/")))
        assertEquals(listOf(Route.Servers, browse("/")), stack)
    }
}
