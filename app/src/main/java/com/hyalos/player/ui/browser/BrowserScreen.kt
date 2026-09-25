package com.hyalos.player.ui.browser

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import com.hyalos.player.R
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
                    Entries(state.items) { item ->
                        val path = RemotePath.join(viewModel.path, item.name)
                        when {
                            item.kind == BrowserItem.Kind.DIRECTORY -> onOpenDirectory(path)
                            item.playable -> onPlay(path)
                            else -> scope.launch { snackbar.showSnackbar(unplayable) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Entries(items: List<BrowserItem>, onClick: (BrowserItem) -> Unit) {
    val context = LocalContext.current
    LazyColumn(Modifier.fillMaxSize()) {
        items(items, key = { it.name }) { item ->
            ListItem(
                modifier = Modifier.clickable { onClick(item) },
                leadingContent = { Icon(painterResource(item.icon()), null) },
                headlineContent = { Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                supportingContent = item.details(context)?.let { { Text(it) } },
            )
        }
    }
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
