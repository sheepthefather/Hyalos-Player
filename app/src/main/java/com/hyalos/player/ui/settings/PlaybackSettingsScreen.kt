package com.hyalos.player.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hyalos.player.R

/**
 * How playback behaves — the choices that do not belong to any one file.
 *
 * Unlike the cache limit, none of these costs anything to change, so they are
 * written as soon as they are touched.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackSettingsScreen(viewModel: PlaybackSettingsViewModel, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_playback)) },
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

            HorizontalDivider()

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

            HorizontalDivider()

            Text(stringResource(R.string.settings_initial_orientation), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.settings_initial_orientation_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ORIENTATIONS.forEach { (orientation, label) ->
                    FilterChip(
                        selected = viewModel.initialOrientation == orientation,
                        onClick = { viewModel.onInitialOrientationChange(orientation) },
                        label = { Text(stringResource(label)) },
                    )
                }
            }
        }
    }
}
