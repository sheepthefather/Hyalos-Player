package com.hyalos.player.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.hyalos.player.AppContainer
import com.hyalos.player.ui.browser.BrowserScreen
import com.hyalos.player.ui.browser.BrowserViewModel
import com.hyalos.player.ui.player.PlayerScreen
import com.hyalos.player.ui.player.PlayerViewModel
import com.hyalos.player.ui.serveredit.ServerEditScreen
import com.hyalos.player.ui.serveredit.ServerEditViewModel
import com.hyalos.player.ui.servers.ServerListScreen
import com.hyalos.player.ui.servers.ServerListViewModel
import com.hyalos.player.ui.settings.SettingsScreen
import com.hyalos.player.ui.settings.SettingsViewModel

@Composable
fun AppNavigation(container: AppContainer) {
    val backStack = rememberNavBackStack(Route.Servers)

    NavDisplay(
        backStack = backStack,
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
            entry<Route.Settings> {
                SettingsScreen(
                    viewModel = viewModel { SettingsViewModel(container) },
                    onBack = { backStack.removeLastOrNull() },
                )
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
                    viewModel = viewModel { PlayerViewModel(container, key.serverId, key.path) },
                    onBack = { backStack.removeLastOrNull() },
                )
            }
        },
    )
}
