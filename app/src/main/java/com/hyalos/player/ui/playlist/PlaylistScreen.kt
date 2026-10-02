package com.hyalos.player.ui.playlist

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hyalos.player.R
import com.hyalos.player.data.BrowserLayout
import com.hyalos.player.ui.common.CenteredMessage
import com.hyalos.player.ui.common.EntryGrid
import com.hyalos.player.ui.common.EntryList

/**
 * One server's playlist, drawn exactly like a directory.
 *
 * Deliberately the same list, the same tiles and the same selection mode as the
 * browser: the entries are films either way, and a second way of showing them
 * would be a second set of habits to learn. What differs is what the entries
 * *are* — this screen cannot open a folder, rename, copy or paste, and its only
 * action takes an entry out of the list without touching the file.
 *
 * There is no sort menu, and there will not be one: sorting would show an order
 * that is not the order they play in, and this screen's whole job is to be the
 * queue. **The order is dragged instead** — the handle on each row moves one
 * entry, and the move is written to the store, so the screen and the queue stay
 * the same thing. That distinction is why a sort menu was refused here and a
 * drag was not: a sort is a *view*, and this list has no view to differ from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistScreen(
    viewModel: PlaylistViewModel,
    onPlay: (String) -> Unit,
    onBack: () -> Unit,
) {
    val state = viewModel.state
    val layout by viewModel.layout.collectAsStateWithLifecycle()
    val serverName by viewModel.serverName.collectAsStateWithLifecycle()
    val grid = layout == BrowserLayout.GRID

    Scaffold(
        topBar = {
            if (viewModel.selecting) {
                SelectionBar(viewModel)
            } else {
                Column {
                    TopAppBar(
                        title = { Text(stringResource(R.string.playlist_title)) },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
                            }
                        },
                        actions = {
                            IconButton(onClick = viewModel::toggleLayout) {
                                if (grid) {
                                    Icon(
                                        painterResource(R.drawable.ic_view_list),
                                        stringResource(R.string.browser_switch_to_list),
                                    )
                                } else {
                                    Icon(
                                        painterResource(R.drawable.ic_grid_view),
                                        stringResource(R.string.browser_switch_to_grid),
                                    )
                                }
                            }
                        },
                    )
                    // Stands where the browser's breadcrumb does: the list belongs
                    // to one server, and "播放列表" alone would not say which.
                    Text(
                        serverName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                    )
                }
            }
        },
    ) { insets ->
        val modifier = Modifier.fillMaxSize().padding(insets)
        when (val current = state) {
            PlaylistViewModel.State.Loading ->
                Box(modifier, Alignment.Center) { CircularProgressIndicator() }

            is PlaylistViewModel.State.Loaded ->
                if (current.rows.isEmpty()) {
                    CenteredMessage(
                        stringResource(R.string.playlist_empty),
                        modifier,
                        secondary = stringResource(R.string.playlist_empty_hint),
                    )
                } else if (grid) {
                    EntryGrid(
                        items = current.rows,
                        loadThumbnail = viewModel::loadThumbnail,
                        selected = viewModel.selected,
                        // Every entry here can play: only playable files are let in.
                        onClick = { viewModel.onTap(it.id) { onPlay(it.id) } },
                        onLongClick = { viewModel.onLongPress(it.id) },
                        // The Scaffold's own padding still has to be applied by
                        // hand — the list draws from the very top otherwise, and
                        // the first row ends up behind the app bar.
                        modifier = modifier,
                    )
                } else {
                    EntryList(
                        items = current.rows,
                        loadThumbnail = viewModel::loadThumbnail,
                        selected = viewModel.selected,
                        onClick = { viewModel.onTap(it.id) { onPlay(it.id) } },
                        onLongClick = { viewModel.onLongPress(it.id) },
                        modifier = modifier,
                        // The one list in the app whose order is the user's to
                        // set. The grid beside it does not take this: reordering
                        // tiles is a different gesture with a different feel, and
                        // a queue is a sequence — one column is how a sequence is
                        // read.
                        onReorder = viewModel::reorder,
                    )
                }
        }
    }

    // While something is selected, back means "leave selection mode" — as in the
    // browser. Without this it pops back to the server list and the selection
    // goes with it.
    BackHandler(enabled = viewModel.selecting) { viewModel.clearSelection() }

    RemoveDialog(viewModel, serverName)
}

/**
 * The toolbar while entries are selected.
 *
 * Replaces the normal one, as in the browser. Delete is the only action: there
 * is nothing here to rename, copy or paste — and select-all is the way to empty
 * a long list without fifty taps.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionBar(viewModel: PlaylistViewModel) {
    TopAppBar(
        title = { Text(stringResource(R.string.selection_count, viewModel.selected.size)) },
        navigationIcon = {
            IconButton(onClick = viewModel::clearSelection) {
                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.selection_close))
            }
        },
        actions = {
            IconButton(onClick = viewModel::selectAll) {
                Icon(painterResource(R.drawable.ic_select_all), stringResource(R.string.selection_all))
            }
            IconButton(onClick = viewModel::askRemove) {
                Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.playlist_remove))
            }
        },
    )
}

/**
 * Confirms taking entries out of the list.
 *
 * Counted, like the browser's delete, so "remove Series 3" reads as "remove 47".
 * It also has to say what is *not* happening: the bin icon invites the reading
 * that these files are being deleted, and they are not.
 */
@Composable
private fun RemoveDialog(viewModel: PlaylistViewModel, serverName: String) {
    if (!viewModel.confirmingRemove) return
    AlertDialog(
        onDismissRequest = viewModel::cancelRemove,
        title = { Text(stringResource(R.string.playlist_remove_title)) },
        text = { Text(stringResource(R.string.playlist_remove_body, serverName, viewModel.selected.size)) },
        confirmButton = {
            TextButton(onClick = viewModel::confirmRemove) {
                Text(stringResource(R.string.playlist_remove))
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::cancelRemove) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
