package com.hyalos.player.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hyalos.player.R
import com.hyalos.player.info.InfoRow
import com.hyalos.player.info.InfoSection

/**
 * A file's facts, laid out. Shared because two screens ask the same question —
 * the browser about a file it has not opened, the player about one it is
 * playing — and only one of them has to wait for an answer.
 *
 * [reading] replaces the rows with a spinner while a header comes off the
 * network. [failed] does *not*: the file's own lines came from the directory
 * listing, cost nothing and stay true, so they are still shown, with the bad
 * news and [onRetry] above them.
 */
@Composable
fun InfoDialog(
    title: String,
    onDismiss: () -> Unit,
    sections: List<InfoSection> = emptyList(),
    reading: Boolean = false,
    failed: Boolean = false,
    onRetry: (() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (reading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(R.string.info_reading))
                }
            } else {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    if (failed) {
                        Text(
                            stringResource(R.string.info_failed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            stringResource(R.string.info_failed_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    InfoSectionList(
                        sections = sections,
                        colors = InfoColors(
                            section = MaterialTheme.colorScheme.primary,
                            label = MaterialTheme.colorScheme.onSurfaceVariant,
                            value = MaterialTheme.colorScheme.onSurface,
                        ),
                    )
                }
            }
        },
        confirmButton = {
            if (failed && onRetry != null) {
                TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
            }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}

/**
 * How the rows are drawn.
 *
 * The layout is shared; the colours are not. The browser shows them on a themed
 * dialog, the player on a scrim over the picture — white on black, because a
 * light Material surface in the middle of a film is a different app for a
 * moment.
 */
data class InfoColors(val section: Color, val label: Color, val value: Color)

/**
 * The sections themselves, as a column. Callers put them where they belong: a
 * dialog, or an overlay on a scrim.
 */
@Composable
fun InfoSectionList(sections: List<InfoSection>, colors: InfoColors, modifier: Modifier = Modifier) {
    Column(modifier) {
        sections.forEach { InfoSectionBlock(it, colors) }
    }
}

@Composable
private fun InfoSectionBlock(section: InfoSection, colors: InfoColors) {
    Text(
        stringResource(section.title),
        style = MaterialTheme.typography.titleSmall,
        color = colors.section,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
    // An empty group means there is nothing of that kind — no audio track, no
    // subtitles — which is worth a line of its own.
    if (section.rows.isEmpty()) {
        InfoLine(InfoRow(R.string.info_track, stringResource(R.string.info_none)), colors)
        return
    }
    section.rows.forEach { InfoLine(it, colors) }
}

/** Label in a fixed column, value wrapping beside it. */
@Composable
private fun InfoLine(row: InfoRow, colors: InfoColors) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            stringResource(row.label),
            style = MaterialTheme.typography.bodySmall,
            color = colors.label,
            modifier = Modifier.width(INFO_LABEL_WIDTH),
        )
        // A null value means the label is the whole statement — see `InfoRow`.
        row.value?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.value)
        }
    }
}

/** Wide enough for the longest label ("设备不支持这条轨道" wraps; "修改时间" does not). */
private val INFO_LABEL_WIDTH = 76.dp
