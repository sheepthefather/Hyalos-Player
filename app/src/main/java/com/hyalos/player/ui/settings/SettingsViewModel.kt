package com.hyalos.player.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hyalos.player.AppContainer
import com.hyalos.player.data.AppSettings
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The cache-limit control has two inputs — a slider and a text field — and both
 * are only written when the user is finished with them.
 *
 * The reason is cost: storing the value triggers eviction, which walks the cache
 * directory. Writing on every slider frame or keystroke would mean dozens of
 * those walks per gesture. So the two controls own their own display state, and
 * only [commitSlider] / [commitCustom] reach the repository.
 */
class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    /** Where the slider sits. Follows the finger, so it must not wait for a write. */
    var sliderMb by mutableStateOf(0f)
        private set

    /** The text field, which is how values past the slider's end are entered. */
    var customMb by mutableStateOf("")
        private set

    /** `null` until the stored value has been read. */
    var storedMb by mutableStateOf<Int?>(null)
        private set

    /** `null` until the cache has been measured. */
    var usageBytes by mutableStateOf<Long?>(null)
        private set

    private var customJob: Job? = null

    val sliderMax = AppSettings.SLIDER_MAX_MB.toFloat()

    init {
        viewModelScope.launch {
            show(container.settings.settings.first().thumbnailCacheMb)
            refreshUsage()
        }
    }

    fun onSliderMove(value: Float) {
        sliderMb = value
    }

    fun onSliderCommit() {
        commit(sliderMb.roundToInt())
    }

    fun onCustomInput(text: String) {
        // Digits only, and bounded: the field feeds a byte count.
        customMb = text.filter { it.isDigit() }.take(6)
        // Debounced rather than committed per keystroke, for the same reason the
        // slider is not: "500" is three writes and three evictions otherwise.
        customJob?.cancel()
        customJob = viewModelScope.launch {
            delay(COMMIT_DELAY_MS)
            customMb.toIntOrNull()?.let { commit(it) }
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            container.thumbnails.clear()
            refreshUsage()
        }
    }

    private fun commit(megabytes: Int) {
        viewModelScope.launch {
            container.settings.setThumbnailCacheMb(megabytes)
            show(megabytes)
            refreshUsage()
        }
    }

    /** Reflect a value in both controls: they are two views of one number. */
    private fun show(megabytes: Int) {
        storedMb = megabytes
        sliderMb = megabytes.coerceIn(0, AppSettings.SLIDER_MAX_MB).toFloat()
        customMb = megabytes.toString()
    }

    private suspend fun refreshUsage() {
        usageBytes = container.thumbnails.usage()
    }

    private companion object {
        const val COMMIT_DELAY_MS = 600L
    }
}
