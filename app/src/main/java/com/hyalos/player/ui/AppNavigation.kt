package com.hyalos.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.hyalos.player.AppContainer
import com.hyalos.player.R
import com.hyalos.player.ui.browser.BrowserScreen
import com.hyalos.player.ui.browser.BrowserViewModel
import com.hyalos.player.ui.player.PlayerScreen
import com.hyalos.player.ui.player.PlayerViewModel
import com.hyalos.player.ui.playlist.PlaylistScreen
import com.hyalos.player.ui.playlist.PlaylistServersScreen
import com.hyalos.player.ui.playlist.PlaylistServersViewModel
import com.hyalos.player.ui.playlist.PlaylistViewModel
import com.hyalos.player.ui.serveredit.ServerEditScreen
import com.hyalos.player.ui.serveredit.ServerEditViewModel
import com.hyalos.player.ui.servers.ServerListScreen
import com.hyalos.player.ui.servers.ServerListViewModel
import com.hyalos.player.ui.settings.AboutScreen
import com.hyalos.player.ui.settings.PlaybackSettingsScreen
import com.hyalos.player.ui.settings.PlaybackSettingsViewModel
import com.hyalos.player.ui.settings.SettingsScreen
import com.hyalos.player.ui.settings.SettingsViewModel
import com.hyalos.player.ui.settings.StorageSettingsScreen
import com.hyalos.player.ui.settings.StorageSettingsViewModel

/** Which half of the app the bottom bar is showing. */
private enum class Tab { Servers, Playlists }

@Composable
fun AppNavigation(container: AppContainer) {
    // One back stack per tab, so switching leaves each where it was rather than
    // unwinding it. Both are saved, so process death restores both.
    val servers = rememberNavBackStack(Route.Servers)
    val playlists = rememberNavBackStack(Route.Playlists)
    var tab by rememberSaveable { mutableStateOf(Tab.Servers) }

    val active = if (tab == Tab.Servers) servers else playlists

    // Back out of the second tab lands on the first, as it does everywhere else
    // on the platform. NavDisplay's own handler stands aside while a stack holds
    // only its root — which is what lets back reach the activity and close the
    // app from the first tab — so this one case has to be said explicitly.
    BackHandler(enabled = tab != Tab.Servers && active.size == 1) { tab = Tab.Servers }

    Scaffold(
        bottomBar = {
            // Roots only. A directory, a playlist and the player all want the
            // whole screen, and the player hides the system bars besides.
            if (active.size == 1) {
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == Tab.Servers,
                        onClick = { tab = Tab.Servers },
                        icon = { Icon(painterResource(R.drawable.ic_dns), null) },
                        label = { Text(stringResource(R.string.tab_servers)) },
                    )
                    NavigationBarItem(
                        selected = tab == Tab.Playlists,
                        onClick = { tab = Tab.Playlists },
                        icon = { Icon(painterResource(R.drawable.ic_playlist), null) },
                        label = { Text(stringResource(R.string.tab_playlists)) },
                    )
                }
            }
        },
    ) { padding ->
        // The player is full-bleed: it hides the system bars and paints its own
        // black surface edge to edge. Padding it for a bar that is not there
        // leaves a strip of theme background along the bottom instead.
        val room = if (active.lastOrNull() is Route.Play) PaddingValues() else padding

        // Each tab is its own call site, so each gets its own composition.
        // Handing one NavDisplay a different stack instead would have its
        // decorators read the swap as a pop and throw the saved state away — and
        // where both stacks hold the same route, share a single ViewModel between
        // them.
        when (tab) {
            Tab.Servers -> RouteStack(servers, container, room)
            Tab.Playlists -> RouteStack(playlists, container, room)
        }
    }
}

@Composable
private fun RouteStack(
    backStack: NavBackStack<NavKey>,
    container: AppContainer,
    padding: PaddingValues,
) {
    NavDisplay(
        backStack = backStack,
        // consumeWindowInsets matters: Material's Scaffold does not consume the
        // insets it hands out, so without it every screen below would apply the
        // system bars a second time and leave a bar's worth of blank at the
        // bottom.
        modifier = Modifier.padding(padding).consumeWindowInsets(padding),
        onBack = { backStack.removeLastOrNull() },
        // The ViewModelStore decorator gives every entry its own ViewModels,
        // scoped to its time on the stack. So `viewModel { … }` inside an entry
        // can capture that entry's key directly, and each directory level keeps
        // its own listing while the user is deeper in.
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = entryProvider {
            entry<Route.Servers> {
                ServerListScreen(
                    viewModel = viewModel { ServerListViewModel(container) },
                    onOpen = { backStack.add(Route.Browse(it.id, it.startPath)) },
                    onAdd = { backStack.add(Route.EditServer()) },
                    onEdit = { backStack.add(Route.EditServer(it.id)) },
                    onOpenSettings = { backStack.add(Route.Settings) },
                )
            }
            entry<Route.Playlists> {
                PlaylistServersScreen(
                    viewModel = viewModel { PlaylistServersViewModel(container) },
                    onOpen = { backStack.add(Route.Playlist(it.id)) },
                )
            }
            entry<Route.Settings> {
                SettingsScreen(
                    viewModel = viewModel { SettingsViewModel(container) },
                    onOpenPlayback = { backStack.add(Route.SettingsPlayback) },
                    onOpenStorage = { backStack.add(Route.SettingsStorage) },
                    onOpenAbout = { backStack.add(Route.SettingsAbout) },
                    onBack = { backStack.removeLastOrNull() },
                )
            }
            entry<Route.SettingsPlayback> {
                PlaybackSettingsScreen(
                    viewModel = viewModel { PlaybackSettingsViewModel(container) },
                    onBack = { backStack.removeLastOrNull() },
                )
            }
            entry<Route.SettingsStorage> {
                StorageSettingsScreen(
                    viewModel = viewModel { StorageSettingsViewModel(container) },
                    onBack = { backStack.removeLastOrNull() },
                )
            }
            entry<Route.SettingsAbout> {
                AboutScreen(onBack = { backStack.removeLastOrNull() })
            }
            entry<Route.EditServer> { key ->
                ServerEditScreen(
                    viewModel = viewModel { ServerEditViewModel(container, key.serverId) },
                    onDone = { backStack.removeLastOrNull() },
                )
            }
            entry<Route.Browse> { key ->
                BrowserScreen(
                    viewModel = viewModel { BrowserViewModel(container, key.serverId, key.path) },
                    onOpenDirectory = { backStack.add(Route.Browse(key.serverId, it)) },
                    onPlay = { backStack.add(Route.Play(key.serverId, it)) },
                    onJumpTo = {
                        val target = Route.Browse(key.serverId, it)
                        backStack.replaceWith(jumpTo(backStack.filterIsInstance<Route>(), target))
                    },
                    onEditServer = { backStack.add(Route.EditServer(key.serverId)) },
                    onBack = { backStack.removeLastOrNull() },
                )
            }
            entry<Route.Play> { key ->
                PlayerScreen(
                    viewModel = viewModel {
                        PlayerViewModel(container, key.serverId, key.path, key.fromPlaylist)
                    },
                    onBack = { backStack.removeLastOrNull() },
                )
            }
            entry<Route.Playlist> { key ->
                PlaylistScreen(
                    viewModel = viewModel { PlaylistViewModel(container, key.serverId) },
                    // Playing from the list means the list is the queue, not the
                    // folder the chosen film happens to sit in.
                    onPlay = { backStack.add(Route.Play(key.serverId, it, fromPlaylist = true)) },
                    onBack = { backStack.removeLastOrNull() },
                )
            }
        },
    )
}
