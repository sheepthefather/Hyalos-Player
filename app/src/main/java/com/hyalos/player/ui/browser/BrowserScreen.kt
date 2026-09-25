package com.hyalos.player.ui.browser

import android.graphics.Bitmap
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hyalos.player.R
import com.hyalos.player.data.BrowserLayout
import com.hyalos.player.data.SortKey
import com.hyalos.player.kernel.RemotePath
import com.hyalos.player.ui.common.CenteredMessage
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

    // Dialogs and the progress indicator live here rather than in the listing,
    // so they survive a refresh that replaces every item.
    RenameDialog(viewModel)
    DeleteDialog(viewModel)
    BusyDialog(viewModel.busy)
    ReportSnackbar(viewModel, snackbar)

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
                if (state.items.isEmpty()) {
                    CenteredMessage(stringResource(R.string.browser_empty))
                } else {
                    val onClick: (BrowserItem) -> Unit = { item ->
                        viewModel.onTap(item) {
                            val path = RemotePath.join(viewModel.path, item.name)
                            when {
                                item.kind == BrowserItem.Kind.DIRECTORY -> onOpenDirectory(path)
                                item.playable -> onPlay(path)
                                else -> scope.launch { snackbar.showSnackbar(unplayable) }
                            }
                        }
                    }
                    val onLongClick: (BrowserItem) -> Unit = viewModel::onLongPress
                    if (grid) {
                        GridEntries(state.items, viewModel::thumbnailFor, viewModel.selected, onClick, onLongClick)
                    } else {
                        ListEntries(state.items, viewModel::thumbnailFor, viewModel.selected, onClick, onLongClick)
                    }
                }
            }
        }
    }
}

@Composable
private fun ListEntries(
    items: List<BrowserItem>,
    loadThumbnail: suspend (BrowserItem) -> Bitmap?,
    selected: Set<String>,
    onClick: (BrowserItem) -> Unit,
    onLongClick: (BrowserItem) -> Unit,
) {
    val context = LocalContext.current
    LazyColumn(Modifier.fillMaxSize()) {
        items(items, key = { it.name }) { item ->
            val isSelected = item.name in selected
            ListItem(
                modifier = Modifier
                    .combinedClickable(onClick = { onClick(item) }, onLongClick = { onLongClick(item) })
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
                    ),
                leadingContent = {
                    // The check sits over the thumbnail rather than replacing it:
                    // the picture is what tells films apart, and hiding it is
                    // exactly what you do not want while choosing among them.
                    Box {
                        ThumbnailFrame(item, loadThumbnail, Modifier.size(THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT))
                        if (isSelected) SelectedBadge(Modifier.align(Alignment.Center))
                    }
                },
                headlineContent = { Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                supportingContent = item.details(context)?.let { { Text(it) } },
            )
        }
    }
}

/** The tick drawn on a selected row or tile. */
@Composable
private fun SelectedBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(24.dp)
            .background(MaterialTheme.colorScheme.primary, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(R.drawable.ic_check),
            null,
            Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

/**
 * The same directory as tiles of thumbnails.
 *
 * The size and date of the list are dropped here — they would crowd a tile —
 * and the frame takes the space instead, which is the point of this view.
 */
@Composable
private fun GridEntries(
    items: List<BrowserItem>,
    loadThumbnail: suspend (BrowserItem) -> Bitmap?,
    selected: Set<String>,
    onClick: (BrowserItem) -> Unit,
    onLongClick: (BrowserItem) -> Unit,
) {
    LazyVerticalGrid(
        // Adaptive rather than a fixed count: the same code gives two columns on
        // a phone held upright and five on a tablet, without asking the width.
        columns = GridCells.Adaptive(minSize = GRID_MIN_CELL),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(items, key = { it.name }) { item ->
            val isSelected = item.name in selected
            Column(
                modifier = Modifier.combinedClickable(
                    onClick = { onClick(item) },
                    onLongClick = { onLongClick(item) },
                ),
            ) {
                Box {
                    ThumbnailFrame(item, loadThumbnail, Modifier.fillMaxWidth().aspectRatio(THUMBNAIL_ASPECT))
                    if (isSelected) {
                        SelectedBadge(Modifier.align(Alignment.TopEnd).padding(4.dp))
                    }
                }
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Unspecified,
                    modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
                )
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
            }
            IconButton(onClick = viewModel::copySelected) {
                Icon(painterResource(R.drawable.ic_copy), stringResource(R.string.action_copy))
            }
            IconButton(onClick = viewModel::cutSelected) {
                Icon(painterResource(R.drawable.ic_cut), stringResource(R.string.action_cut))
            }
            IconButton(onClick = viewModel::askDelete) {
                Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.action_delete))
            }
        },
    )
}

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

/**
 * A video's frame, or an icon for everything else.
 *
 * Shared by both views so the lazy-loading rule lives in one place.
 *
 * [produceState] is what makes extraction lazy: the box composes when its row or
 * tile scrolls into view and is cancelled when it leaves, so no frame is ever
 * pulled for a film nobody is looking at. Its key is the entry's name, so a new
 * directory does not reuse the previous one's frames.
 */
@Composable
private fun ThumbnailFrame(
    item: BrowserItem,
    loadThumbnail: suspend (BrowserItem) -> Bitmap?,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant, shape),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap by produceState<Bitmap?>(initialValue = null, item.name) {
            value = loadThumbnail(item)
        }
        val frame = bitmap
        if (frame == null) {
            Icon(
                painterResource(item.icon()),
                null,
                Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Image(
                bitmap = frame.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(shape),
            )
        }
    }
}

private val THUMBNAIL_WIDTH = 64.dp
private val THUMBNAIL_HEIGHT = 36.dp
private val THUMBNAIL_ASPECT = 16f / 9f

/** Narrower would fit three columns of unreadably small tiles on a phone. */
private val GRID_MIN_CELL = 150.dp

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

private fun BrowserItem.icon(): Int = when (kind) {
    BrowserItem.Kind.DIRECTORY -> R.drawable.ic_folder
    BrowserItem.Kind.VIDEO -> R.drawable.ic_movie
    BrowserItem.Kind.AUDIO -> R.drawable.ic_music_note
    BrowserItem.Kind.OTHER -> R.drawable.ic_draft
}

/** "1.4 GB · 2024/3/5", or whichever half is known. */
private fun BrowserItem.details(context: android.content.Context): String? {
    val size = size?.let { Formatter.formatShortFileSize(context, it) }
    val date = modifiedMs?.let {
        DateUtils.formatDateTime(
            context,
            it,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_NUMERIC_DATE,
        )
    }
    return listOfNotNull(size, date).joinToString(" · ").ifEmpty { null }
}
