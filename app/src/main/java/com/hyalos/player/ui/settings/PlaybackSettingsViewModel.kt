package com.hyalos.player.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hyalos.player.AppContainer
import com.hyalos.player.data.AppSettings
import com.hyalos.player.data.PlaybackOrientation
import com.hyalos.player.data.VideoScale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The three playback choices that belong to no one file.
 *
 * Read once and then written optimistically. "Once" is every visit: the entry's
 * ViewModelStore is cleared when the page is popped, so re-entering builds a
 * fresh ViewModel that re-reads — no need to keep collecting.
 */
class PlaybackSettingsViewModel(private val container: AppContainer) : ViewModel() {

    /** Whether finishing a film starts the next one in its folder. */
    var autoPlayNext by mutableStateOf(AppSettings().autoPlayNext)
        private set

    /** How video is fitted to the screen. */
    var videoScale by mutableStateOf(AppSettings().videoScale)
        private set

    /** Which way up the player opens. */
    var initialOrientation by mutableStateOf(AppSettings().initialOrientation)
        private set

    init {
        viewModelScope.launch {
            val settings = container.settings.settings.first()
            autoPlayNext = settings.autoPlayNext
            videoScale = settings.videoScale
            initialOrientation = settings.initialOrientation
        }
    }

    fun onAutoPlayNextChange(enabled: Boolean) {
        autoPlayNext = enabled
        viewModelScope.launch { container.settings.setAutoPlayNext(enabled) }
    }

    fun onVideoScaleChange(scale: VideoScale) {
        videoScale = scale
        viewModelScope.launch { container.settings.setVideoScale(scale) }
    }

    fun onInitialOrientationChange(orientation: PlaybackOrientation) {
        initialOrientation = orientation
        viewModelScope.launch { container.settings.setInitialOrientation(orientation) }
    }
}
