package com.hyalos.player.ui.local

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.hyalos.player.R
import com.hyalos.player.data.LocalSource
import com.hyalos.player.ui.browser.BrowserScreen
import com.hyalos.player.ui.browser.BrowserViewModel
import com.hyalos.player.ui.common.CenteredMessage

/**
 * The local tab: the device's own storage, or the one screen that asks for it.
 *
 * There is no second browser behind this. Once the permission is there, this is
 * [BrowserScreen] over a session whose source is the device — the same list, the
 * same grid, the same sort, the same search, the same player. The only thing
 * that differs is what `SessionManager` hands the kernel.
 */
@Composable
fun LocalScreen(
    viewModel: BrowserViewModel,
    onOpenDirectory: (path: String) -> Unit,
    onPlay: (path: String) -> Unit,
    onJumpTo: (path: String) -> Unit,
) {
    var granted by remember { mutableStateOf(LocalSource.granted()) }

    // Re-checked when this screen comes back. The permission is granted on a
    // settings screen this app does not own, so returning from it is the only
    // moment the answer can have changed — and returning from it is a resume.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = LocalSource.granted() }

    if (granted) {
        BrowserScreen(
            viewModel = viewModel,
            onOpenDirectory = onOpenDirectory,
            onPlay = onPlay,
            onJumpTo = onJumpTo,
            // Nowhere above this to go back to, and no server to edit: this
            // screen *is* the tab's root, and the source is the device.
            onEditServer = null,
            onBack = null,
        )
    } else {
        LocalPermission()
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
