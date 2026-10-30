package com.hyalos.player.ui.editor

import org.junit.Assert.assertArrayEquals
import org.junit.Test

/**
 * Numbering the rows the field actually produced.
 *
 * The cases are the ones a plain "count the newlines" would get wrong: wrapping
 * into several rows, a trailing newline that creates an empty last line, and a
 * run of blank lines that still counts.
 */
class LineNumbersTest {

    @Test
    fun `each line of an unwrapped text gets the next number`() {
        val text = "one\ntwo\nthree"
        // One visual row per logical line.
        assertArrayEquals(intArrayOf(1, 2, 3), lineNumbers(intArrayOf(0, 4, 8), text))
    }

    @Test
    fun `a wrapped line numbers its first row and leaves the rest blank`() {
        val text = "a long line that wrapped\nafter"
        // The first row wraps at offset 10; the second logical line starts at 25,
        // right after the newline at 24.
        assertArrayEquals(intArrayOf(1, 0, 2), lineNumbers(intArrayOf(0, 10, 25), text))
    }

    @Test
    fun `a trailing newline makes a final empty line, and it is numbered`() {
        val text = "one\n"
        assertArrayEquals(intArrayOf(1, 2), lineNumbers(intArrayOf(0, 4), text))
    }

    @Test
    fun `an empty text is one line`() {
        assertArrayEquals(intArrayOf(1), lineNumbers(intArrayOf(0), ""))
    }

    @Test
    fun `blank lines are numbered like any other`() {
        val text = "a\n\n\nb"
        assertArrayEquals(intArrayOf(1, 2, 3, 4), lineNumbers(intArrayOf(0, 2, 3, 4), text))
    }

    @Test
    fun `a wrapped row that begins mid-line does not consume the next number`() {
        // "aaa" wrapped after two characters: the row at 2 starts on 'a', not
        // after a newline, so it carries no number.
        assertArrayEquals(intArrayOf(1, 0, 2), lineNumbers(intArrayOf(0, 2, 4), "aaa\nbbb"))
    }
}
