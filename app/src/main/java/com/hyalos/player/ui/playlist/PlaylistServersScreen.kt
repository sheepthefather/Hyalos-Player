package com.hyalos.player.ui.playlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hyalos.player.R
import com.hyalos.player.data.ServerConfig
import com.hyalos.player.ui.common.CenteredMessage

/**
 * Which server's playlist to open.
 *
 * The lists are per server, so this is the step between the tab and one of them.
 * It lists every server rather than only the ones with something in them: a
 * server that vanished from this screen would look like a server that vanished,
 * and the row itself says which is which.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistServersScreen(
    viewModel: PlaylistServersViewModel,
    onOpen: (ServerConfig) -> Unit,
) {
    val choices by viewModel.choices.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.playlist_title)) }) },
    ) { insets ->
        val list = choices
        when {
            list == null -> Box(Modifier.fillMaxSize().padding(insets), Alignment.Center) {
                CircularProgressIndicator()
            }
            list.isEmpty() -> CenteredMessage(
                stringResource(R.string.servers_empty),
                Modifier.padding(insets),
                secondary = stringResource(R.string.playlist_no_servers_hint),
            )
            else -> LazyColumn(Modifier.fillMaxSize().padding(insets)) {
                items(list, key = { it.server.id }) { choice ->
                    ListItem(
                        modifier = Modifier.clickable { onOpen(choice.server) },
                        headlineContent = { Text(choice.server.name) },
                        // Only whether there is anything, not the address: this
                        // screen is about picking one, and the address is already
                        // written on the servers tab.
                        supportingContent = {
                            Text(
                                stringResource(
                                    if (choice.hasEntries) R.string.playlist_has_entries
                                    else R.string.playlist_no_entries,
                                ),
                            )
                        },
                        trailingContent = {
                            Icon(painterResource(R.drawable.ic_chevron_right), null)
                        },
                    )
                }
            }
        }
    }
}
