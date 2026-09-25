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

/**
 * The thumbnail cache: what it may use, what it is using, and the way to empty
 * it. The page is titled 存储 rather than 视频缩略图 because the thumbnail cache
 * is the only thing stored today, and the section keeps its own heading.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageSettingsScreen(viewModel: StorageSettingsViewModel, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_storage)) },
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

@Composable
private fun CacheLimit(viewModel: StorageSettingsViewModel) {
    val stored = viewModel.storedMb ?: return

    Text(
        stringResource(R.string.settings_cache_limit) + "：" + cacheLimitLabel(stored),
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
        Text("${AppSettings.SLIDER_MAX_MB} MB", style = MaterialTheme.typography.bodySmall)
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
private fun Usage(viewModel: StorageSettingsViewModel) {
    val context = LocalContext.current
    val used = viewModel.usageBytes
    val limit = viewModel.storedMb
    if (used == null || limit == null) return

    val usedText = remember(used) { Formatter.formatShortFileSize(context, used) }
    // Not remembered: reading a string resource is a composition-time lookup, and
    // it is what the index reports for the same number.
    val limitText = cacheLimitLabel(limit)
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

/** 0, 50, 100 … 1000: a 50 MB step is fine enough to aim at. */
private const val SLIDER_STEPS = 19
