package com.hyalos.player.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hyalos.player.AppContainer
import com.hyalos.player.data.AppSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * The settings index. It owns no control — only the values its rows report.
 *
 * Collected rather than read once, because the pages it links to write to the
 * same repository while this one sits below them on the stack.
 *
 * [SharingStarted.Eagerly] rather than `WhileSubscribed`: this screen is not
 * composed while a sub-page is on top, so a lazy start would re-subscribe on the
 * way back and emit [AppSettings]'s defaults for a frame — the summaries would
 * flash the wrong values before settling.
 */
class SettingsViewModel(container: AppContainer) : ViewModel() {
    val settings: StateFlow<AppSettings> = container.settings.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
}
