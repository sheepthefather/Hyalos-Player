package com.hyalos.player.ui.editor

/**
 * Which line number belongs to each **visual** line of a laid-out text.
 *
 * A text field wraps: one logical line can occupy several rows on screen. The
 * gutter must number the logical lines, so the continuation rows of a wrapped
 * line get `0` — nothing is drawn there, which is what every editor does and
 * what stops the numbers from drifting out of step with the text.
 *
 * [lineStarts] are the character offsets where each visual line begins, in
 * order, which is exactly what `TextLayoutResult.getLineStart` gives. A visual
 * row that begins right after a newline — or at the very start — is the first
 * row of its logical line.
 *
 * Pure, and separate from Compose so it can be tested without a device: the
 * arithmetic is the part that can be wrong, not the drawing.
 */
internal fun lineNumbers(lineStarts: IntArray, text: String): IntArray {
    val numbers = IntArray(lineStarts.size)
    var number = 0
    for (i in lineStarts.indices) {
        val start = lineStarts[i]
        val beginsLine = start == 0 || (start in 1..text.length && text[start - 1] == '\n')
        if (beginsLine) {
            number++
            numbers[i] = number
        }
    }
    // An empty text still has one line, and it is line 1 — the field reports a
    // single layout line whose start is 0, so this only guards the degenerate
    // case of being handed no lines at all.
    return numbers
}
