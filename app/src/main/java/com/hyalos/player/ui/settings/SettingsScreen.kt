package com.hyalos.player.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hyalos.player.BuildConfig
import com.hyalos.player.R

/**
 * The settings index: one row per page, each reporting the value it edits.
 *
 * The summaries exist so the split into pages costs nothing to read — a row that
 * only said "播放" would make the current picture invisible from here, and the
 * user would have to walk into every page to find out what the app is set to.
 *
 * Only settings are reported, never the cache *usage*: that one needs a disk
 * measurement, and this screen is not rebuilt when a sub-page changes it, so the
 * number would be stale exactly when it matters. It stays live on the storage
 * page, one tap away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onOpenPlayback: () -> Unit,
    onOpenStorage: () -> Unit,
    onOpenAbout: () -> Unit,
    onBack: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

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
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsEntry(
                title = stringResource(R.string.settings_playback),
                summary = stringResource(
                    R.string.settings_playback_summary,
                    stringResource(if (settings.autoPlayNext) R.string.state_on else R.string.state_off),
                    stringResource(scaleLabel(settings.videoScale)),
                    stringResource(orientationLabel(settings.initialOrientation)),
                ),
                onClick = onOpenPlayback,
            )
            SettingsEntry(
                title = stringResource(R.string.settings_storage),
                summary = stringResource(
                    R.string.settings_storage_summary,
                    cacheLimitLabel(settings.thumbnailCacheMb),
                ),
                onClick = onOpenStorage,
            )
            SettingsEntry(
                title = stringResource(R.string.settings_about),
                summary = stringResource(R.string.settings_about_summary, BuildConfig.VERSION_NAME),
                onClick = onOpenAbout,
            )
        }
    }
}

/** A row that leads somewhere, in the shape [ListItem] gives the server list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsEntry(title: String, summary: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        trailingContent = { Icon(painterResource(R.drawable.ic_chevron_right), null) },
    )
}
