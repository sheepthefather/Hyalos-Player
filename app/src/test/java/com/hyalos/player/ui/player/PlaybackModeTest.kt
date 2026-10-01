package com.hyalos.player.ui.player

import androidx.media3.common.Player
import com.hyalos.player.data.AppSettings
import com.hyalos.player.data.JsonSerializer
import com.hyalos.player.data.PlaybackMode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * The four modes, as the two answers the player actually needs from them.
 *
 * Worth testing because the mapping is where the modes stop being four names
 * and start being behaviour, and because two of the four look alike from the
 * outside: `SEQUENCE` and `ONCE` both leave `repeatMode` off, and are told
 * apart only by whether the queue holds the folder.
 *
 * `repeatMode` is the one that bites. Every mode has to set it, including the
 * ones that keep the queue as it is — a `ONCE` that inherits `REPEAT_ALL` from
 * the mode before it repeats forever under a button that says it will not.
 */
class PlaybackModeTest {

    @Test
    fun sequence_plays_the_folder_and_stops() {
        assertEquals(Player.REPEAT_MODE_OFF, PlaybackMode.SEQUENCE.repeatMode)
        assertTrue(PlaybackMode.SEQUENCE.wantsFullQueue)
    }

    @Test
    fun repeat_all_plays_the_folder_and_wraps() {
        assertEquals(Player.REPEAT_MODE_ALL, PlaybackMode.REPEAT_ALL.repeatMode)
        assertTrue(PlaybackMode.REPEAT_ALL.wantsFullQueue)
    }

    @Test
    fun repeat_one_repeats_the_film() {
        assertEquals(Player.REPEAT_MODE_ONE, PlaybackMode.REPEAT_ONE.repeatMode)
        // The queue is still built. It costs nothing at playback time, keeps
        // the next/previous buttons meaningful, and makes switching to one of
        // the folder modes free.
        assertTrue(PlaybackMode.REPEAT_ONE.wantsFullQueue)
    }

    @Test
    fun once_plays_one_film_and_stops() {
        assertEquals(Player.REPEAT_MODE_OFF, PlaybackMode.ONCE.repeatMode)
        assertFalse(PlaybackMode.ONCE.wantsFullQueue)
    }

    /**
     * Stated on its own because it is the failure that would be hardest to
     * notice: `ONCE` is the one mode whose queue is a single item, so a repeat
     * mode left over from before would loop that one item forever.
     */
    @Test
    fun a_single_item_queue_is_never_left_repeating() {
        for (mode in PlaybackMode.values()) {
            if (!mode.wantsFullQueue) {
                assertEquals(
                    "$mode trims the queue to one item, so a repeat mode left over " +
                        "from before would loop that one item forever",
                    Player.REPEAT_MODE_OFF,
                    mode.repeatMode,
                )
            }
        }
    }

    @Test
    fun cycling_visits_every_mode_and_comes_back() {
        val seen = mutableListOf<PlaybackMode>()
        var mode = PlaybackMode.SEQUENCE
        repeat(PlaybackMode.values().size) {
            seen += mode
            mode = mode.next
        }
        assertEquals(PlaybackMode.values().toList(), seen)
        assertEquals(PlaybackMode.SEQUENCE, mode)
    }

    /**
     * The order is not incidental: it is the order the settings page lists them
     * in, so the list and the button have to agree about what follows what.
     */
    @Test
    fun the_cycle_order_is_the_declaration_order() {
        assertEquals(PlaybackMode.REPEAT_ALL, PlaybackMode.SEQUENCE.next)
        assertEquals(PlaybackMode.REPEAT_ONE, PlaybackMode.REPEAT_ALL.next)
        assertEquals(PlaybackMode.ONCE, PlaybackMode.REPEAT_ONE.next)
        assertEquals(PlaybackMode.SEQUENCE, PlaybackMode.ONCE.next)
    }

    /**
     * Four modes, one button — so the icon is the whole of what it says, and
     * two modes sharing one would make a mode unreachable by looking.
     *
     * A copy-paste slip in the `when` is exactly the mistake this catches, and
     * it is invisible on screen until someone counts the taps.
     */
    @Test
    fun every_mode_has_its_own_icon() {
        val icons = PlaybackMode.values().map { it.iconRes }
        assertEquals(icons.size, icons.toSet().size)
    }

    /** The same slip as the icons, one step further out. */
    @Test
    fun every_mode_has_its_own_name() {
        val names = PlaybackMode.values().map { it.labelRes }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun the_default_mode_plays_the_folder() {
        assertEquals(PlaybackMode.SEQUENCE, AppSettings().playbackMode)
    }

    /**
     * A settings file written before modes existed.
     *
     * It has `autoPlayNext` and no `playbackMode`. `ignoreUnknownKeys` drops the
     * old field and the new one takes its default, so the file loads rather
     * than being reported as corrupt and resetting everything else with it.
     *
     * The old value is **not** carried over: `false` meant "stop after this
     * one", which is `ONCE`, but writing that migration would mean keeping a
     * field alive for one release to guess at. The mode is one tap away in the
     * player; losing the rest of the settings file is not.
     */
    @Test
    fun a_file_from_before_modes_still_loads() = runTest {
        val old = """
            {"thumbnailCacheMb":250,"browserLayout":"GRID","sortKey":"SIZE",
             "sortAscending":false,"autoPlayNext":false,"videoScale":"ZOOM",
             "initialOrientation":"PORTRAIT"}
        """.trimIndent()

        val settings = JsonSerializer(AppSettings.serializer(), AppSettings())
            .readFrom(ByteArrayInputStream(old.encodeToByteArray()))

        assertEquals(PlaybackMode.SEQUENCE, settings.playbackMode)
        // Everything else in the file survives, which is the point.
        assertEquals(250, settings.thumbnailCacheMb)
        assertEquals(false, settings.sortAscending)
    }

    /** The names are storage: the file is read back by them. */
    @Test
    fun a_mode_is_written_out_by_name() {
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        val written = json.encodeToString(AppSettings.serializer(), AppSettings(playbackMode = PlaybackMode.REPEAT_ONE))

        assertTrue(written, written.contains("\"REPEAT_ONE\""))
    }
}
