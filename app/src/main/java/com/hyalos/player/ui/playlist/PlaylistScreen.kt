package com.hyalos.player.ui.playlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hyalos.player.R
import com.hyalos.player.kernel.RemotePath
import com.hyalos.player.ui.common.CenteredMessage

/**
 * The queue a user built by hand, one server's worth.
 *
 * Unlike the folder a film happens to sit in, the entries here come from
 * anywhere on the share — which is why each row says where it is. Two folders
 * holding "Episode 1.mkv" would otherwise be indistinguishable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistScreen(
    viewModel: PlaylistViewModel,
    onPlay: (String) -> Unit,
    onBack: () -> Unit,
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val serverName by viewModel.serverName.collectAsStateWithLifecycle()
    var confirmingClear by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.playlist_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
                    }
                },
                actions = {
                    // Nothing to clear when there is nothing there.
                    if (!entries.isNullOrEmpty()) {
                        TextButton(onClick = { confirmingClear = true }) {
                            Text(stringResource(R.string.playlist_clear))
                        }
                    }
                },
            )
        },
    ) { insets ->
        val list = entries
        when {
            list == null -> Box(Modifier.fillMaxSize().padding(insets), Alignment.Center) {
                CircularProgressIndicator()
            }
            list.isEmpty() -> CenteredMessage(
                stringResource(R.string.playlist_empty),
                Modifier.padding(insets),
                secondary = stringResource(R.string.playlist_empty_hint),
            )
            else -> Column(Modifier.fillMaxSize().padding(insets)) {
                Text(
                    serverName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                )
                Button(
                    onClick = { onPlay(list.first()) },
                    modifier = Modifier.padding(16.dp),
                ) {
                    Text(stringResource(R.string.playlist_play_all))
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    // The path is the key and the identity: entries are unique,
                    // and this screen deletes by the same string.
                    items(list, key = { it }) { path ->
                        PlaylistRow(
                            path = path,
                            onPlay = { onPlay(path) },
                            onRemove = { viewModel.remove(path) },
                        )
                    }
                }
            }
        }
    }

    val count = entries?.size ?: 0
    if (confirmingClear && count > 0) {
        AlertDialog(
            onDismissRequest = { confirmingClear = false },
            title = { Text(stringResource(R.string.playlist_clear_title)) },
            text = { Text(stringResource(R.string.playlist_clear_body, serverName, count)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clear()
                        confirmingClear = false
                    },
                ) { Text(stringResource(R.string.playlist_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingClear = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistRow(path: String, onPlay: () -> Unit, onRemove: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onPlay),
        headlineContent = { Text(RemotePath.name(path)) },
        // Where it lives, not what it is: the whole point of this list is that its
        // entries come from different folders.
        supportingContent = { Text(RemotePath.parent(path) ?: RemotePath.ROOT) },
        trailingContent = {
            // A cross, not a bin: this takes the entry out of the list, and says
            // nothing about the file, which is still on the server.
            IconButton(onClick = onRemove) {
                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.playlist_remove))
            }
        },
    )
}
