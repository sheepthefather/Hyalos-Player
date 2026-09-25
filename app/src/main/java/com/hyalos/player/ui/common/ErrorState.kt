package com.hyalos.player.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hyalos.player.R

@Composable
fun UiError.text(): String =
    if (arg == null) stringResource(message) else stringResource(message, arg)

/** A full-screen error with the action [UiError] suggests. */
@Composable
fun ErrorState(
    error: UiError,
    onRetry: () -> Unit,
    onEditServer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CenteredMessage(error.text(), modifier) {
        when (error.action) {
            UiError.Action.RETRY -> Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
            UiError.Action.EDIT_SERVER ->
                Button(onClick = onEditServer) { Text(stringResource(R.string.action_edit_server)) }
            UiError.Action.NONE -> {}
        }
    }
}

/** A message in the middle of the screen, with optional content below it. */
@Composable
fun CenteredMessage(
    text: String,
    modifier: Modifier = Modifier,
    secondary: String? = null,
    below: @Composable () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (secondary != null) {
            Text(
                secondary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        below()
    }
}
