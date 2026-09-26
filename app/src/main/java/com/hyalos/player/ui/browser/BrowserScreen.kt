package com.hyalos.player.ui.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hyalos.player.R
import com.hyalos.player.data.BrowserLayout
import com.hyalos.player.data.SortKey
import com.hyalos.player.info.InfoRow
import com.hyalos.player.info.InfoSection
import com.hyalos.player.kernel.RemotePath
import com.hyalos.player.ui.common.CenteredMessage
import com.hyalos.player.ui.common.EntryGrid
import com.hyalos.player.ui.common.EntryList
import com.hyalos.player.ui.common.EntryRow
import com.hyalos.player.ui.common.ErrorState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel,
    onOpenDirectory: (path: String) -> Unit,
    onPlay: (path: String) -> Unit,
    onJumpTo: (path: String) -> Unit,
    onEditServer: () -> Unit,
    onBack: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val unplayable = stringResource(R.string.browser_unplayable)
    val title = RemotePath.name(viewModel.path).ifEmpty { viewModel.serverName }
    val layout by viewModel.layout.collectAsStateWithLifecycle()
    val sortKey by viewModel.sortKey.collectAsStateWithLifecycle()
    val sortAscending by viewModel.sortAscending.collectAsStateWithLifecycle()
    val clipboard by viewModel.clipboard.collectAsStateWithLifecycle()
    val grid = layout == BrowserLayout.GRID

    // While something is selected, back means "leave selection mode". Without
    // this it climbs out of the directory instead, taking the selection with it
    // — which reads as having lost it, or worse, as having done something.
    BackHandler(enabled = viewModel.selecting) { viewModel.clearSelection() }

    // Dialogs and the progress indicator live here rather than in the listing,
    // so they survive a refresh that replaces every item.
    RenameDialog(viewModel)
    DeleteDialog(viewModel)
    InfoDialog(viewModel)
    BusyDialog(viewModel.busy)
    ReportSnackbar(viewModel, snackbar)
    PlaylistSnackbar(viewModel, snackbar)

    Scaffold(
        topBar = {
            Column {
                if (viewModel.selecting) {
                    SelectionBar(viewModel)
                } else {
                    TopAppBar(
                        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
                            }
                        },
                        actions = {
                            if (clipboard != null) {
                                IconButton(onClick = viewModel::paste) {
                                    Icon(painterResource(R.drawable.ic_paste), stringResource(R.string.action_paste))
                                }
                            }
                            SortMenu(
                                key = sortKey,
                                ascending = sortAscending,
                                onPickKey = viewModel::setSortKey,
                                onPickDirection = viewModel::setSortAscending,
                            )
                            // The icon shows the view being switched *to*, which is
                            // what tapping it will do; the description says so in words.
                            IconButton(onClick = viewModel::toggleLayout) {
                                Icon(
                                    painterResource(if (grid) R.drawable.ic_view_list else R.drawable.ic_grid_view),
                                    stringResource(
                                        if (grid) R.string.browser_switch_to_list else R.string.browser_switch_to_grid,
                                    ),
                                )
                            }
                            IconButton(onClick = viewModel::refresh) {
                                Icon(painterResource(R.drawable.ic_refresh), stringResource(R.string.refresh))
                            }
                        },
                    )
                    Breadcrumbs(viewModel.path, viewModel.serverName, onJumpTo)
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { insets ->
        val modifier = Modifier.fillMaxSize().padding(insets)
        when (val state = viewModel.state) {
            BrowserViewModel.State.Loading -> Box(modifier, Alignment.Center) { CircularProgressIndicator() }
            is BrowserViewModel.State.Failed ->
                ErrorState(state.error, onRetry = viewModel::retry, onEditServer = onEditServer, modifier = modifier)
            is BrowserViewModel.State.Loaded -> PullToRefreshBox(
                isRefreshing = viewModel.refreshing,
                onRefresh = viewModel::refresh,
                modifier = modifier,
            ) {
                if (state.rows.isEmpty()) {
                    CenteredMessage(stringResource(R.string.browser_empty))
                } else {
                    // The rows carry only what the list draws; what an entry *is*
                    // — a folder, a film, something with no player — stays here,
                    // which is why the tap looks the item back up.
                    val byName = remember(state.items) { state.items.associateBy { it.name } }
                    val onClick: (EntryRow) -> Unit = { row ->
                        viewModel.onTap(row.id) {
                            val item = byName[row.id] ?: return@onTap
                            val path = RemotePath.join(viewModel.path, item.name)
                            when {
                                item.kind == BrowserItem.Kind.DIRECTORY -> onOpenDirectory(path)
                                item.playable -> onPlay(path)
                                else -> scope.launch { snackbar.showSnackbar(unplayable) }
                            }
                        }
                    }
                    if (grid) {
                        EntryGrid(
                            items = state.rows,
                            loadThumbnail = viewModel::loadThumbnail,
                            selected = viewModel.selected,
                            onClick = onClick,
                            onLongClick = { viewModel.onLongPress(it.id) },
                        )
                    } else {
                        EntryList(
                            items = state.rows,
                            loadThumbnail = viewModel::loadThumbnail,
                            selected = viewModel.selected,
                            onClick = onClick,
                            onLongClick = { viewModel.onLongPress(it.id) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The toolbar while items are selected.
 *
 * It **replaces** the normal one rather than appearing beside it: with items
 * checked, refresh, sort and the view toggle act on nothing the user is
 * looking at, and leaving them there would invite taps that quietly do nothing
 * to the selection.
 *
 * Rename is the one action that only means something for a single item, so it
 * is the one that can be unavailable — which is where "a menu for one item" and
 * "act on many" genuinely differ, and the reason one mode can serve both.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionBar(viewModel: BrowserViewModel) {
    val single = (viewModel.state as? BrowserViewModel.State.Loaded)
        ?.items
        ?.firstOrNull { it.name in viewModel.selected }
        ?.takeIf { viewModel.selected.size == 1 }

    TopAppBar(
        title = { Text(stringResource(R.string.selection_count, viewModel.selected.size)) },
        navigationIcon = {
            IconButton(onClick = viewModel::clearSelection) {
                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.selection_close))
            }
        },
        actions = {
            if (single != null) {
                IconButton(onClick = { viewModel.startRename(single) }) {
                    Icon(painterResource(R.drawable.ic_edit), stringResource(R.string.action_rename))
                }
                // Beside rename, because it is the other thing that only makes
                // sense for exactly one file — and only for one that can be
                // played: "what codec is this" is a question about media.
                if (single.playable) {
                    IconButton(onClick = { viewModel.showInfo(single) }) {
                        Icon(painterResource(R.drawable.ic_info), stringResource(R.string.action_info))
                    }
                }
            }
            IconButton(onClick = viewModel::copySelected) {
                Icon(painterResource(R.drawable.ic_copy), stringResource(R.string.action_copy))
            }
            IconButton(onClick = viewModel::cutSelected) {
                Icon(painterResource(R.drawable.ic_cut), stringResource(R.string.action_cut))
            }
            // Before delete: the one destructive action stays last.
            IconButton(onClick = viewModel::addToPlaylist) {
                Icon(painterResource(R.drawable.ic_playlist), stringResource(R.string.action_add_to_playlist))
            }
            IconButton(onClick = viewModel::askDelete) {
                Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.action_delete))
            }
        },
    )
}

/**
 * What the file says about itself, over what the listing already knew.
 *
 * Three shapes in one dialog, because they are the same dialog: reading (a
 * spinner — a header comes off the network), done, and done-but-the-header-
 * could-not-be-read. The last is not an empty dialog: the file's own lines cost
 * nothing and are still true, so they are shown with the failure above them and
 * a way to try again.
 */
@Composable
private fun InfoDialog(viewModel: BrowserViewModel) {
    val state = viewModel.info ?: return

    AlertDialog(
        onDismissRequest = viewModel::dismissInfo,
        title = { Text(stringResource(R.string.info_title)) },
        text = {
            when (state) {
                BrowserViewModel.InfoState.Reading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(R.string.info_reading))
                }

                is BrowserViewModel.InfoState.Ready -> Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                ) {
                    if (state.failed) {
                        Text(
                            stringResource(R.string.info_failed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            stringResource(R.string.info_failed_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    state.sections.forEach { section -> InfoSectionBlock(section) }
                }
            }
        },
        confirmButton = {
            if (state is BrowserViewModel.InfoState.Ready && state.failed) {
                TextButton(onClick = viewModel::retryInfo) { Text(stringResource(R.string.retry)) }
            }
            TextButton(onClick = viewModel::dismissInfo) { Text(stringResource(R.string.close)) }
        },
    )
}

@Composable
private fun InfoSectionBlock(section: InfoSection) {
    Text(
        stringResource(section.title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
    // An empty group means there is nothing of that kind — no audio track, no
    // subtitles — which is worth a line of its own.
    if (section.rows.isEmpty()) {
        InfoLine(InfoRow(R.string.info_track, stringResource(R.string.info_none)))
        return
    }
    section.rows.forEach { InfoLine(it) }
}

/** Label in a fixed column, value wrapping beside it. */
@Composable
private fun InfoLine(row: InfoRow) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            stringResource(row.label),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(INFO_LABEL_WIDTH),
        )
        // A null value means the label is the whole statement — see `InfoRow`.
        row.value?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Wide enough for the longest label ("修改时间"), narrow enough for the values. */
private val INFO_LABEL_WIDTH = 76.dp

@Composable
private fun RenameDialog(viewModel: BrowserViewModel) {
    val item = viewModel.renaming ?: return
    var name by remember(item.name) { mutableStateOf(item.name) }

    AlertDialog(
        onDismissRequest = viewModel::cancelRename,
        title = { Text(stringResource(R.string.rename_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.rename_field)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { viewModel.commitRename(name.trim()) },
                // An empty name is not a name; the server would reject it and
                // the error would be about the wrong thing.
                enabled = name.isNotBlank() && name.trim() != item.name,
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = viewModel::cancelRename) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun DeleteDialog(viewModel: BrowserViewModel) {
    val prompt = viewModel.deletePrompt ?: return
    AlertDialog(
        onDismissRequest = viewModel::cancelDelete,
        title = { Text(stringResource(R.string.delete_title)) },
        text = {
            // The count is the whole point: "delete Series 3" and "delete 47
            // items" are different decisions, and only one of them is informed.
            Text(
                stringResource(
                    if (prompt.truncated) R.string.delete_body_at_least else R.string.delete_body,
                    prompt.total,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = viewModel::confirmDelete) { Text(stringResource(R.string.action_delete)) }
        },
        dismissButton = {
            TextButton(onClick = viewModel::cancelDelete) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/**
 * Progress for the operation in flight.
 *
 * Indeterminate: per-item progress would need a callback interface across the
 * FFI, and a server-side copy of a large film finishes in seconds. A modal
 * dialog rather than a bar in the toolbar because the operations block the
 * directory from being used anyway.
 */
@Composable
private fun BusyDialog(operation: BrowserViewModel.Operation?) {
    val label = when (operation) {
        null -> return
        BrowserViewModel.Operation.RENAME -> R.string.busy_rename
        BrowserViewModel.Operation.DELETE -> R.string.busy_delete
        BrowserViewModel.Operation.COPY -> R.string.busy_copy
        BrowserViewModel.Operation.MOVE -> R.string.busy_move
    }
    AlertDialog(
        onDismissRequest = {},
        confirmButton = {},
        text = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Text(stringResource(label))
            }
        },
    )
}

/** Reports what an operation managed, including the parts that did not. */
@Composable
private fun ReportSnackbar(viewModel: BrowserViewModel, host: SnackbarHostState) {
    val report = viewModel.report ?: return
    val text = report.text()
    LaunchedEffect(report) {
        host.showSnackbar(text)
        viewModel.dismissReport()
    }
}

/** Confirms an add to the playlist, including what did not go in. */
@Composable
private fun PlaylistSnackbar(viewModel: BrowserViewModel, host: SnackbarHostState) {
    val notice = viewModel.playlistNotice ?: return
    val text = when {
        notice.added == 0 -> stringResource(R.string.playlist_added_none)
        notice.skipped == 0 -> stringResource(R.string.playlist_added)
        else -> stringResource(R.string.playlist_added_some, notice.added, notice.skipped)
    }
    LaunchedEffect(notice) {
        host.showSnackbar(text)
        viewModel.dismissPlaylistNotice()
    }
}

@Composable
private fun BrowserViewModel.Report.text(): String {
    val base = stringResource(R.string.report_done, result.succeeded)
    val failed = if (result.failures.isEmpty()) "" else stringResource(R.string.report_failed, result.failures.size)
    val conflicts = if (result.conflicts.isEmpty()) "" else stringResource(R.string.report_conflicts, result.conflicts.size)
    return base + failed + conflicts
}

/**
 * Picks what a directory is ordered by, and which way.
 *
 * The two are one menu rather than two because they are one decision: "date,
 * newest first" is a single intent, and splitting it would mean dismissing one
 * menu to open another. A check mark on each row shows what is currently set,
 * so neither the key nor the direction has to be remembered.
 */
@Composable
private fun SortMenu(
    key: SortKey,
    ascending: Boolean,
    onPickKey: (SortKey) -> Unit,
    onPickDirection: (Boolean) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val keys = listOf(
        SortKey.NAME to R.string.sort_name,
        SortKey.DATE to R.string.sort_date,
        SortKey.SIZE to R.string.sort_size,
        SortKey.TYPE to R.string.sort_type,
    )

    Box {
        IconButton(onClick = { open = true }) {
            Icon(painterResource(R.drawable.ic_sort), stringResource(R.string.browser_sort))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            keys.forEach { (option, label) ->
                MenuRow(
                    label = stringResource(label),
                    selected = option == key,
                    onClick = {
                        open = false
                        onPickKey(option)
                    },
                )
            }
            HorizontalDivider()
            MenuRow(
                label = stringResource(R.string.sort_ascending),
                selected = ascending,
                onClick = {
                    open = false
                    onPickDirection(true)
                },
            )
            MenuRow(
                label = stringResource(R.string.sort_descending),
                selected = !ascending,
                onClick = {
                    open = false
                    onPickDirection(false)
                },
            )
        }
    }
}

@Composable
private fun MenuRow(label: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        trailingIcon = {
            if (selected) {
                Icon(painterResource(R.drawable.ic_check), null, Modifier.size(20.dp))
            }
        },
        onClick = onClick,
    )
}

/** Every ancestor of [path], tappable. The root is labelled with the server's name. */
@Composable
private fun Breadcrumbs(path: String, serverName: String, onJumpTo: (String) -> Unit) {
    val ancestors = RemotePath.ancestors(path)
    val state = rememberLazyListState()
    // Keep the current directory in view when the trail is wider than the screen.
    LaunchedEffect(path) { state.scrollToItem(ancestors.lastIndex) }

    LazyRow(
        state = state,
        contentPadding = PaddingValues(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(ancestors) { index, ancestor ->
            if (index > 0) {
                Icon(
                    painterResource(R.drawable.ic_chevron_right),
                    null,
                    Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val current = index == ancestors.lastIndex
            val label = RemotePath.name(ancestor).ifEmpty {
                serverName.ifEmpty { stringResource(R.string.browser_root) }
            }
            TextButton(onClick = { onJumpTo(ancestor) }, enabled = !current) {
                Text(label, maxLines = 1)
            }
        }
    }
}

