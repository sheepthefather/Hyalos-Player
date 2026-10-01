package com.hyalos.player.ui.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which of the local tab's places are worth offering.
 *
 * The rule is one line and worth pinning all the same, because the failure it
 * prevents is quiet on the machine it is written on: a developer's phone has
 * every one of these directories, so a list that never filtered anything would
 * look right there and offer dead entries on a new one.
 */
class LocalPlacesTest {

    @Test
    fun the_root_is_offered_even_when_nothing_else_exists() {
        val places = placesThatExist { false }

        assertEquals(listOf<Int?>(null), places.map { it.directory })
    }

    @Test
    fun a_directory_that_is_not_there_is_not_offered() {
        val places = placesThatExist { it == LocalDirectories.DOWNLOAD }

        assertEquals(
            listOf(null, LocalDirectories.DOWNLOAD),
            places.map { it.directory },
        )
    }

    @Test
    fun everything_that_is_there_is_offered_in_the_declared_order() {
        val places = placesThatExist { true }

        assertEquals(LOCAL_PLACES, places)
    }

    /** `DCIM` is a camera's folder and stays away until something has taken a picture. */
    @Test
    fun the_camera_folder_is_among_the_ones_that_can_be_absent() {
        assertTrue(LOCAL_PLACES.any { it.directory == LocalDirectories.CAMERA })
        assertEquals(false, placesThatExist { it != LocalDirectories.CAMERA }
            .any { it.directory == LocalDirectories.CAMERA })
    }

    @Test
    fun the_root_is_first() {
        assertEquals(null, LOCAL_PLACES.first().directory)
    }

    @Test
    fun every_place_but_the_root_opens_a_path_inside_the_session() {
        assertEquals("/", LOCAL_PLACES.first().path())
        assertEquals(
            "/${LocalDirectories.DOWNLOAD}",
            LOCAL_PLACES.first { it.directory == LocalDirectories.DOWNLOAD }.path(),
        )
    }

    /** Two places opening the same directory would be one place offered twice. */
    @Test
    fun no_two_places_name_the_same_directory() {
        val names = LOCAL_PLACES.map { it.directory }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun every_place_has_a_label_and_an_icon() {
        LOCAL_PLACES.forEach {
            assertTrue("a place needs a label", it.label != 0)
            assertTrue("a place needs an icon", it.icon != 0)
        }
    }
}
