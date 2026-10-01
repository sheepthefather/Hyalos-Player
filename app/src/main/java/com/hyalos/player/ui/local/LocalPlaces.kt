package com.hyalos.player.ui.local

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.hyalos.player.R

/**
 * The names of the platform's shared directories, spelled out.
 *
 * `Environment.DIRECTORY_DOWNLOADS` and its siblings are the authority for
 * these, and **not** used, for one reason: on the JVM the `android.jar` a unit
 * test compiles against is the mockable one, where every static field is null.
 * Reading them here would leave `directory` null on every entry, which this
 * file's own filter reads as "the root" — so the list would silently be seven
 * copies of one place, and no unit test could tell.
 *
 * That they match the platform is pinned instead by
 * `LocalPlacesPlatformTest`, which runs on a device where the constants are
 * real. Spell them out here, check them there.
 */
internal object LocalDirectories {
    const val DOWNLOAD = "Download"
    const val CAMERA = "DCIM"
    const val PICTURES = "Pictures"
    const val MOVIES = "Movies"
    const val MUSIC = "Music"
    const val DOCUMENTS = "Documents"
}

/**
 * The places the local tab offers as a way in.
 *
 * The tab used to open straight onto the root of shared storage, which is one
 * tap fewer for that one destination and one tap more for every other — and
 * "every other" is where things actually are: a film that was downloaded, a
 * clip just shot, the pictures folder. So the root is now one entry among
 * several rather than the only one.
 *
 * It costs nothing in practice. Each tab keeps its own back stack, so the extra
 * tap to reach the root is paid once per run of the app, not once per visit.
 */

/**
 * One place.
 *
 * [directory] is a name under shared storage, or null for shared storage
 * itself — the one entry that is not a shortcut, because it is not a way into
 * something, it is all of it.
 */
internal data class LocalPlace(
    @StringRes val label: Int,
    val directory: String?,
    @DrawableRes val icon: Int,
)

/**
 * In the order they are offered: the root first, then the directories in the
 * order someone is most likely to want them.
 *
 * The names are the platform's ([LocalDirectories]); the labels are ours,
 * because the tab is in Chinese and the folder on disk is not.
 */
internal val LOCAL_PLACES = listOf(
    LocalPlace(R.string.place_root, null, R.drawable.ic_storage),
    LocalPlace(R.string.place_download, LocalDirectories.DOWNLOAD, R.drawable.ic_download),
    LocalPlace(R.string.place_camera, LocalDirectories.CAMERA, R.drawable.ic_camera),
    LocalPlace(R.string.place_pictures, LocalDirectories.PICTURES, R.drawable.ic_photos),
    LocalPlace(R.string.place_movies, LocalDirectories.MOVIES, R.drawable.ic_movie),
    LocalPlace(R.string.place_music, LocalDirectories.MUSIC, R.drawable.ic_music_note),
    LocalPlace(R.string.place_documents, LocalDirectories.DOCUMENTS, R.drawable.ic_draft),
)

/**
 * The places worth offering, given which directories are really there.
 *
 * The root is always kept: it is shared storage, it exists by definition, and
 * it is where the tab used to open — dropping it would strand anyone whose
 * media is in a folder none of the others name.
 *
 * The rest are shortcuts, and **a shortcut to a directory that is not there is
 * a door onto an error message**. `DCIM` does not exist until something has
 * taken a picture; `Music` not until something has been copied into it. On a
 * new phone most of this list would be dead entries.
 *
 * [exists] is passed in rather than read here so this stays arithmetic: the
 * caller knows what "there" means and this only decides what that implies.
 */
internal fun placesThatExist(exists: (String) -> Boolean): List<LocalPlace> =
    LOCAL_PLACES.filter { it.directory == null || exists(it.directory) }

/**
 * The path this place opens, on the local session's own stack.
 *
 * Session-relative, exactly as a server's share is: the kernel is pointed at
 * shared storage, so "/" is the root of it and "/Download" is the folder inside.
 */
internal fun LocalPlace.path(): String = directory?.let { "/$it" } ?: "/"
