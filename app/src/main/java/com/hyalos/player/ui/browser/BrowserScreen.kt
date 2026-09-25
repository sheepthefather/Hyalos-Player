package com.hyalos.player.ui.browser

import android.graphics.Bitmap
import android.text.format.DateUtils
import android.text.format.Formatter
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
    val grid = layout == BrowserLayout.GRID

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
                        }
                    },
                    actions = {
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
                        val path = RemotePath.join(viewModel.path, item.name)
                        when {
                            item.kind == BrowserItem.Kind.DIRECTORY -> onOpenDirectory(path)
                            item.playable -> onPlay(path)
                            else -> scope.launch { snackbar.showSnackbar(unplayable) }
                        }
                    }
                    if (grid) {
                        GridEntries(state.items, viewModel::thumbnailFor, onClick)
                    } else {
                        ListEntries(state.items, viewModel::thumbnailFor, onClick)
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
    onClick: (BrowserItem) -> Unit,
) {
    val context = LocalContext.current
    LazyColumn(Modifier.fillMaxSize()) {
        items(items, key = { it.name }) { item ->
            ListItem(
                modifier = Modifier.clickable { onClick(item) },
                leadingContent = {
                    ThumbnailFrame(item, loadThumbnail, Modifier.size(THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT))
                },
                headlineContent = { Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                supportingContent = item.details(context)?.let { { Text(it) } },
            )
        }
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
    onClick: (BrowserItem) -> Unit,
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
            Column(modifier = Modifier.clickable { onClick(item) }) {
                ThumbnailFrame(item, loadThumbnail, Modifier.fillMaxWidth().aspectRatio(THUMBNAIL_ASPECT))
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
                )
            }
        }
    }
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
