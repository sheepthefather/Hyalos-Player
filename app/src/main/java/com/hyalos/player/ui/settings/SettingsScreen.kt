package com.hyalos.player.ui.settings

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import com.hyalos.player.data.VideoScale
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hyalos.player.R
import com.hyalos.player.data.AppSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
                    }
                },
            )
        },
    ) { insets ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(insets)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PlaybackSection(viewModel)

            HorizontalDivider()

            Text(stringResource(R.string.settings_thumbnails), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.settings_cache_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CacheLimit(viewModel)
            Usage(viewModel)
        }
    }
}

/**
 * How playback behaves — the two choices that do not belong to any one file.
 *
 * Unlike the cache limit below, neither of these costs anything to change, so
 * they are written as soon as they are touched.
 */
@Composable
private fun PlaybackSection(viewModel: SettingsViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.settings_playback), style = MaterialTheme.typography.titleMedium)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_auto_next), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.settings_auto_next_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = viewModel.autoPlayNext,
                onCheckedChange = viewModel::onAutoPlayNextChange,
                modifier = Modifier.padding(start = 16.dp),
            )
        }

        Text(stringResource(R.string.settings_video_scale), style = MaterialTheme.typography.bodyLarge)
        Text(
            stringResource(R.string.settings_video_scale_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SCALES.forEach { (scale, label) ->
                FilterChip(
                    selected = viewModel.videoScale == scale,
                    onClick = { viewModel.onVideoScaleChange(scale) },
                    label = { Text(stringResource(label)) },
                )
            }
        }
    }
}

/** In the order they are shown; `FIT` first because it is the default. */
private val SCALES = listOf(
    VideoScale.FIT to R.string.scale_fit,
    VideoScale.FILL to R.string.scale_fill,
    VideoScale.ZOOM to R.string.scale_zoom,
)

@Composable
private fun CacheLimit(viewModel: SettingsViewModel) {
    val stored = viewModel.storedMb ?: return

    Text(
        stringResource(R.string.settings_cache_limit) + "：" + limitLabel(stored),
        style = MaterialTheme.typography.bodyLarge,
    )
    Slider(
        value = viewModel.sliderMb,
        onValueChange = viewModel::onSliderMove,
        // Committed on release, not on every frame: storing the value evicts,
        // and eviction walks the cache directory.
        onValueChangeFinished = viewModel::onSliderCommit,
        valueRange = 0f..viewModel.sliderMax,
        steps = SLIDER_STEPS,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.settings_cache_off), style = MaterialTheme.typography.bodySmall)
        Text(
            "${AppSettings.SLIDER_MAX_MB} MB",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    OutlinedTextField(
        value = viewModel.customMb,
        onValueChange = viewModel::onCustomInput,
        label = { Text(stringResource(R.string.settings_cache_custom)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Usage(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val used = viewModel.usageBytes
    val limit = viewModel.storedMb
    if (used == null || limit == null) return

    val usedText = remember(used) { Formatter.formatShortFileSize(context, used) }
    val limitText = remember(limit) { if (limit == 0) context.getString(R.string.settings_cache_off) else "$limit MB" }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            stringResource(R.string.settings_cache_usage, usedText, limitText),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = viewModel::clearCache) {
            Text(stringResource(R.string.settings_cache_clear))
        }
    }
}

@Composable
private fun limitLabel(megabytes: Int): String =
    if (megabytes == 0) stringResource(R.string.settings_cache_off) else "$megabytes MB"

/** 0, 50, 100 … 1000: a 50 MB step is fine enough to aim at. */
private const val SLIDER_STEPS = 19
