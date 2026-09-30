package com.hyalos.player.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the search box means, and what it lets through. */
class EntrySearchTest {

    // ------------------------------------------------------------------- plain

    @Test
    fun `an empty box matches everything`() {
        assertEquals(EntrySearch.Off, entrySearchOf("", asRegex = false))
        assertEquals(EntrySearch.Off, entrySearchOf("", asRegex = true))
    }

    @Test
    fun `a box of spaces is still empty`() {
        // Otherwise a stray space would hide every name without one.
        assertEquals(EntrySearch.Off, entrySearchOf("   ", asRegex = false))
        assertEquals(EntrySearch.Off, entrySearchOf("   ", asRegex = true))
    }

    @Test
    fun `plain text matches anywhere in the name`() {
        val search = entrySearchOf("1080", asRegex = false)
        assertTrue(search.allows("测试影片 1080p.webm"))
        assertFalse(search.allows("sample.mp4"))
    }

    @Test
    fun `plain text ignores case`() {
        val search = entrySearchOf("SAMPLE", asRegex = false)
        assertTrue(search.allows("sample.mp4"))
    }

    @Test
    fun `plain text treats punctuation as itself`() {
        // The whole reason plain text is the default: "(1)" is a file name, not
        // a capture group, and "1080p.webm" is not a pattern ending in "any
        // character".
        assertTrue(entrySearchOf("(1)", asRegex = false).allows("第(1)集.mkv"))
        assertTrue(entrySearchOf("p.w", asRegex = false).allows("1080p.webm"))
        assertFalse(entrySearchOf("p.w", asRegex = false).allows("1080pxwebm"))
    }

    @Test
    fun `plain text ignores the spaces around it`() {
        val search = entrySearchOf("  1080  ", asRegex = false)
        assertTrue(search.allows("测试影片 1080p.webm"))
        assertEquals(EntrySearch.Text("1080"), search)
    }

    // ------------------------------------------------------------------ regex

    @Test
    fun `a pattern matches anywhere in the name`() {
        val search = entrySearchOf("""第\d+集""", asRegex = true)
        assertTrue(search.allows("第01集 测试文件.txt"))
        assertFalse(search.allows("第末集 测试文件.txt"))
    }

    @Test
    fun `a pattern can anchor`() {
        val search = entrySearchOf(""".*\.webm$""", asRegex = true)
        assertTrue(search.allows("测试影片 1080p.webm"))
        assertFalse(search.allows("sample.mp4"))
    }

    @Test
    fun `a pattern ignores case by default`() {
        val search = entrySearchOf("sample", asRegex = true)
        assertTrue(search.allows("SAMPLE.MP4"))
    }

    @Test
    fun `a pattern can ask for case back`() {
        val search = entrySearchOf("(?-i)sample", asRegex = true)
        assertTrue(search.allows("sample.mp4"))
        assertFalse(search.allows("SAMPLE.MP4"))
    }

    @Test
    fun `a pattern is not trimmed`() {
        // Leading and trailing spaces are part of a pattern — `^ ` is a
        // statement about where a name starts, and trimming it would quietly
        // change what it matches.
        val search = entrySearchOf("^第", asRegex = true)
        assertTrue(search.allows("第01集.txt"))
        assertFalse(search.allows("序 第01集.txt"))
    }

    // ----------------------------------------------------------------- broken

    @Test
    fun `a pattern that does not compile matches nothing`() {
        val search = entrySearchOf("第(01集", asRegex = true)
        assertTrue(search is EntrySearch.Broken)
        assertFalse(search.allows("第01集 测试文件.txt"))
    }

    @Test
    fun `a broken pattern carries the reason`() {
        val search = entrySearchOf("第(01集", asRegex = true) as EntrySearch.Broken
        assertTrue("reason was blank", search.reason.isNotBlank())
        // One line: the parser's message is a description, the pattern, and a
        // caret line, and only the first says anything new.
        assertFalse(search.reason.contains('\n'))
    }

    @Test
    fun `the same text is fine as plain text`() {
        // Which is the point of the switch: the characters are only special
        // while it is on.
        val search = entrySearchOf("第(01集", asRegex = false)
        assertTrue(search.allows("第(01集 测试文件.txt"))
    }

    // -------------------------------------------------------------- filtering

    @Test
    fun `an empty box is not filtering`() {
        assertFalse(entrySearchOf("", asRegex = false).filtering)
    }

    @Test
    fun `anything typed is filtering`() {
        assertTrue(entrySearchOf("a", asRegex = false).filtering)
        assertTrue(entrySearchOf("a", asRegex = true).filtering)
        // Even when it matches nothing at all — the list is being narrowed, and
        // the screen has to say so rather than say the directory is empty.
        assertTrue(entrySearchOf("(", asRegex = true).filtering)
    }
}
