package com.hyalos.player.ui.local

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hyalos.player.R
import com.hyalos.player.data.BrowserLayout
import com.hyalos.player.data.LocalSource
import com.hyalos.player.ui.common.CenteredMessage
import com.hyalos.player.ui.common.EntryGrid
import com.hyalos.player.ui.common.EntryList
import com.hyalos.player.ui.common.EntryRow
import java.io.File

/**
 * The local tab: the places worth starting from, or the one screen that asks for
 * the permission to reach them.
 *
 * A list rather than the browser itself, so the tab reads like the servers one —
 * pick a place, then browse it. There is still no second browser behind this: a
 * place opens `Route.Browse` over the local session, which is the same browser,
 * the same grid, the same sort and the same player a server gets. The only thing
 * that differs is what `SessionManager` hands the kernel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalScreen(viewModel: LocalViewModel, onOpenPlace: (path: String) -> Unit) {
    var granted by remember { mutableStateOf(LocalSource.granted()) }

    // Re-checked when this screen comes back. The permission is granted on a
    // settings screen this app does not own, so returning from it is the only
    // moment the answer can have changed — and returning from it is a resume.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = LocalSource.granted() }

    if (!granted) {
        LocalPermission()
        return
    }

    // Read once per grant rather than per composition: it touches the
    // filesystem, and the answer cannot change while the screen is up — the
    // permission is granted on a settings screen, and coming back from it is
    // what re-runs this.
    val places = remember(granted) {
        placesThatExist { File(LocalSource.root, it).isDirectory }
    }

    val layout by viewModel.layout.collectAsStateWithLifecycle()
    val grid = layout == BrowserLayout.GRID

    // The places as the browser's own rows, so both views come for nothing: a
    // place is a row with an icon and no frame to extract, which is exactly what
    // the browser already draws for a folder.
    val rows = places.map { place ->
        EntryRow(
            id = place.path(),
            name = stringResource(place.label),
            // One line. What a place is called on disk (`DCIM`, `Download`) is
            // not what it is called in the tab, and saying both would be two
            // names for one door.
            detail = null,
            icon = place.icon,
            thumbnail = null,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tab_local)) },
                actions = {
                    IconButton(onClick = viewModel::toggleLayout) {
                        Icon(
                            painterResource(if (grid) R.drawable.ic_view_list else R.drawable.ic_grid_view),
                            stringResource(
                                if (grid) R.string.browser_switch_to_list else R.string.browser_switch_to_grid,
                            ),
                        )
                    }
                },
            )
        },
    ) { insets ->
        val open: (EntryRow) -> Unit = { onOpenPlace(it.id) }
        // Nothing here is selectable, so the selection machinery is handed an
        // empty set and a long press that does nothing: a place cannot be
        // renamed, deleted or copied, it is a door.
        if (grid) {
            EntryGrid(
                items = rows,
                loadThumbnail = { null },
                selected = emptySet(),
                onClick = open,
                onLongClick = {},
                modifier = Modifier.padding(insets),
            )
        } else {
            EntryList(
                items = rows,
                loadThumbnail = { null },
                selected = emptySet(),
                onClick = open,
                onLongClick = {},
                modifier = Modifier.padding(insets),
            )
        }
    }
}

/**
 * What the tab shows before the permission is granted.
 *
 * A page of explanation rather than a prompt, because there is no prompt to
 * show: `MANAGE_EXTERNAL_STORAGE` is not a runtime permission, and the only way
 * to grant it is a settings screen. Saying why the app wants something this
 * broad is the least it can do before sending someone there.
 */
@Composable
private fun LocalPermission() {
    val context = LocalContext.current
    CenteredMessage(
        text = stringResource(R.string.local_permission_title),
        secondary = stringResource(R.string.local_permission_body),
    ) {
        Button(onClick = { context.openAllFilesAccess() }) {
            Text(stringResource(R.string.local_permission_grant))
        }
    }
}

/**
 * Sends the user to the one screen where this can be turned on.
 *
 * The per-app page is preferred — it is one switch, already labelled with this
 * app's name — with the full app list as a fallback, for a device whose settings
 * app has no per-app page. If neither opens, nothing happens: there is no
 * further fallback, and a crash over a missing settings screen would be worse
 * than a button that did nothing.
 */
private fun Context.openAllFilesAccess() {
    val perApp = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
        .setData(Uri.fromParts("package", packageName, null))
    val allApps = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
    runCatching { startActivity(perApp) }.recoverCatching { startActivity(allApps) }
}
