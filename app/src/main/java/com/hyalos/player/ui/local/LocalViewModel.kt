package com.hyalos.player.ui.local

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hyalos.player.AppContainer
import com.hyalos.player.data.BrowserLayout
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The local tab's one piece of state that is not the places themselves: how they
 * are laid out.
 *
 * The same `browserLayout` the browser and the playlist read, and not a third
 * preference of its own. "I like my files as tiles" is a statement about the
 * person, not about which screen they are on, and a switch that had to be
 * thrown again on every screen would be worse than no switch.
 */
class LocalViewModel(private val container: AppContainer) : ViewModel() {

    val layout: StateFlow<BrowserLayout> = container.settings.settings
        .map { it.browserLayout }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BrowserLayout.LIST)

    fun toggleLayout() {
        viewModelScope.launch {
            container.settings.setBrowserLayout(
                if (layout.value == BrowserLayout.LIST) BrowserLayout.GRID else BrowserLayout.LIST,
            )
        }
    }
}
