package com.hyalos.player.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemainingTimeTest {

    @Test
    fun `it counts down, with the sign that says so`() {
        // The sign is the whole point: this sits where the elapsed time usually
        // sits, so `12:34` would read as the position rather than as what is
        // left.
        assertEquals("-12:34", remainingText(durationMs = 1_000_000, positionMs = 246_000))
    }

    @Test
    fun `a film shorter than a minute still shows seconds`() {
        assertEquals("-0:30", remainingText(durationMs = 30_000, positionMs = 0))
        assertEquals("-0:05", remainingText(durationMs = 30_000, positionMs = 25_000))
    }

    @Test
    fun `hours appear only when there are hours`() {
        assertEquals("-59:59", remainingText(durationMs = 3_599_000, positionMs = 0))
        assertEquals("-1:00:00", remainingText(durationMs = 3_600_000, positionMs = 0))
        assertEquals("-2:03:04", remainingText(durationMs = 7_384_000, positionMs = 0))
    }

    @Test
    fun `the last second reads zero rather than going negative`() {
        // A player parked on the final frame reports a position a shade past the
        // duration often enough that `-0:00` turning into `--0:01` is not
        // hypothetical.
        assertEquals("-0:00", remainingText(durationMs = 30_000, positionMs = 30_000))
        assertEquals("-0:00", remainingText(durationMs = 30_000, positionMs = 30_500))
        assertEquals("-0:00", remainingText(durationMs = 30_000, positionMs = 60_000))
    }

    @Test
    fun `no duration means no countdown`() {
        // `C.TIME_UNSET` reaches here as a large negative; so does a zero length
        // for a stream that has not opened yet.
        assertEquals(null, remainingText(durationMs = Long.MIN_VALUE + 1, positionMs = 0))
        assertEquals(null, remainingText(durationMs = 0, positionMs = 0))
        assertNull(remainingText(durationMs = -1, positionMs = 0))
    }

    @Test
    fun `part-seconds are dropped rather than rounded up`() {
        // Truncated, like Media3's own time views: the readout never claims a
        // second that has not started. Written down because the first version of
        // this test asserted the opposite and the code was right.
        assertEquals("-0:00", remainingText(durationMs = 30_000, positionMs = 29_500))
        assertEquals("-0:01", remainingText(durationMs = 30_000, positionMs = 29_000))
    }
}
