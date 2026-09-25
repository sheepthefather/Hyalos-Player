package com.hyalos.player.ui.servers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hyalos.player.R
import com.hyalos.player.data.ServerConfig
import com.hyalos.player.ui.common.CenteredMessage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerListScreen(
    viewModel: ServerListViewModel,
    onOpen: (ServerConfig) -> Unit,
    onAdd: () -> Unit,
    onEdit: (ServerConfig) -> Unit,
) {
    val servers by viewModel.servers.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<ServerConfig?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.servers_title)) }) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                Icon(painterResource(R.drawable.ic_add), stringResource(R.string.server_add))
            }
        },
    ) { insets ->
        val list = servers
        when {
            list == null -> Box(Modifier.fillMaxSize().padding(insets), Alignment.Center) {
                CircularProgressIndicator()
            }
            list.isEmpty() -> CenteredMessage(
                stringResource(R.string.servers_empty),
                Modifier.padding(insets),
                secondary = stringResource(R.string.servers_empty_hint),
            )
            else -> LazyColumn(Modifier.fillMaxSize().padding(insets)) {
                items(list, key = { it.id }) { server ->
                    ServerRow(
                        server,
                        onOpen = { onOpen(server) },
                        onEdit = { onEdit(server) },
                        onDelete = { pendingDelete = server },
                    )
                }
            }
        }
    }

    pendingDelete?.let { server ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.server_delete_title)) },
            text = { Text(stringResource(R.string.server_delete_body, server.name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(server)
                    pendingDelete = null
                }) { Text(stringResource(R.string.server_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun ServerRow(
    server: ServerConfig,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val address = server.smbUri()
    ListItem(
        modifier = Modifier.clickable(onClick = onOpen),
        leadingContent = { Icon(painterResource(R.drawable.ic_dns), null) },
        headlineContent = { Text(server.name) },
        supportingContent = {
            val user = server.username?.takeIf { it.isNotBlank() }
            Text(if (user == null) address else stringResource(R.string.server_summary_user, user, address))
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.more))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.server_edit)) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_edit), null) },
                        onClick = {
                            menuOpen = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.server_delete)) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_delete), null) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        },
    )
}
