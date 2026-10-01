package com.hyalos.player.ui.local

import android.os.Environment
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * That this app's directory names are the platform's.
 *
 * `LocalDirectories` spells them out because a unit test cannot read the real
 * ones — the `android.jar` it compiles against is the mockable one, where every
 * static field is null, so `Environment.DIRECTORY_DOWNLOADS` would come back
 * null and the list would quietly collapse into seven copies of the root.
 *
 * Spelling them out buys testability and costs the link to the platform, and
 * this is where that link is checked instead. It has to run on a device,
 * because on a device the constants are real — which is also the only place
 * they can be.
 *
 * A failure here means a shortcut has stopped naming a directory that exists.
 * Nothing else would notice: `placesThatExist` would simply stop offering it,
 * which looks exactly like a phone that has never had one.
 */
@RunWith(AndroidJUnit4::class)
class LocalPlacesPlatformTest {

    @Test
    fun the_directory_names_are_the_platforms() {
        assertEquals(Environment.DIRECTORY_DOWNLOADS, LocalDirectories.DOWNLOAD)
        assertEquals(Environment.DIRECTORY_DCIM, LocalDirectories.CAMERA)
        assertEquals(Environment.DIRECTORY_PICTURES, LocalDirectories.PICTURES)
        assertEquals(Environment.DIRECTORY_MOVIES, LocalDirectories.MOVIES)
        assertEquals(Environment.DIRECTORY_MUSIC, LocalDirectories.MUSIC)
        assertEquals(Environment.DIRECTORY_DOCUMENTS, LocalDirectories.DOCUMENTS)
    }

    /**
     * And that each one is somewhere under the storage this app browses, rather
     * than an absolute path or a relative one that would escape it.
     */
    @Test
    fun every_directory_is_a_name_and_not_a_path() {
        val names = listOf(
            LocalDirectories.DOWNLOAD,
            LocalDirectories.CAMERA,
            LocalDirectories.PICTURES,
            LocalDirectories.MOVIES,
            LocalDirectories.MUSIC,
            LocalDirectories.DOCUMENTS,
        )
        names.forEach { name ->
            assertEquals("$name should not be a path", -1, name.indexOf('/'))
            assertEquals("$name should not be empty", true, name.isNotEmpty())
        }
    }
}
