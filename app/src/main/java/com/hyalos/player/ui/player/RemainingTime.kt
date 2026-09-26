package com.hyalos.player.ui.player

/**
 * How much of the film is left, written the way the player shows it: `-12:34`.
 *
 * **The sign is not decoration.** This readout sits on the left, which is where
 * the *elapsed* time sits in the landscape bar and where it sits in most players
 * — a bare `12:34` there would be read as the position, saying the opposite of
 * what it means. The minus is what makes it a countdown.
 *
 * Hours appear only when there are any, so an ordinary film shows the short form
 * and a long one is not mistaken for minutes.
 *
 * `null` when the length is not known: something that has not reported a
 * duration yet — a stream still opening, or one that never will — has no
 * "remaining" to speak of, and `-00:00` would be a lie rather than a blank.
 */
internal fun remainingText(durationMs: Long, positionMs: Long): String? {
    if (durationMs <= 0) return null
    val leftMs = (durationMs - positionMs).coerceAtLeast(0)
    val seconds = leftMs / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    return if (hours > 0) {
        "-%d:%02d:%02d".format(hours, minutes % 60, seconds % 60)
    } else {
        "-%d:%02d".format(minutes, seconds % 60)
    }
}
