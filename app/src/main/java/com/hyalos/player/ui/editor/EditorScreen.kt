package com.hyalos.player.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hyalos.player.R
import com.hyalos.player.files.SaveStage
import com.hyalos.player.files.openWith
import com.hyalos.player.ui.common.CenteredMessage
import com.hyalos.player.ui.common.ErrorState
import com.hyalos.player.ui.common.sizeText
import com.hyalos.player.ui.editor.EditorViewModel.Notice
import com.hyalos.player.ui.editor.EditorViewModel.Refusal
import com.hyalos.player.ui.editor.EditorViewModel.SaveFailure
import com.hyalos.player.ui.editor.EditorViewModel.State
import kotlinx.coroutines.launch

/**
 * The text editor: one file, its bytes kept as they were, and a field to change
 * the text in.
 *
 * Deliberately plain. No line numbers, no highlighting, no auto-focus — opening
 * a file to read it should not raise the keyboard, and everything beyond a field
 * is a decision this screen has no way to make for the file it was handed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(viewModel: EditorViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var askingDiscard by remember { mutableStateOf(false) }
    var askingLarge by remember { mutableStateOf(false) }
    val state = viewModel.state

    // Back means "leave" only when there is nothing to lose. With unsaved
    // changes it means "ask", and the answer decides.
    val leave: () -> Unit = { if (viewModel.dirty) askingDiscard = true else onBack() }
    BackHandler(enabled = viewModel.dirty) { askingDiscard = true }

    // A file the editor prepared — the buffer, or a download — goes to the
    // chooser here, where a Context is. `openWith` counts handlers itself, so a
    // refusal is ordinary news rather than a silent nothing.
    val noApp = stringResource(R.string.open_no_app)
    LaunchedEffect(viewModel.readyToOpen) {
        val file = viewModel.readyToOpen ?: return@LaunchedEffect
        // Consuming changes this effect's key and cancels it at the next
        // suspension, so the message goes out on the screen's own scope.
        viewModel.consumeReadyToOpen()
        if (!openWith(context, file)) scope.launch { snackbar.showSnackbar(noApp) }
    }

    NoticeSnackbar(viewModel, snackbar)
    SaveFailureDialog(viewModel)
    DiscardDialog(
        name = viewModel.name,
        asking = askingDiscard,
        onDiscard = {
            askingDiscard = false
            onBack()
        },
        onCancel = { askingDiscard = false },
    )
    LargeOpenDialog(
        asking = askingLarge,
        name = viewModel.name,
        size = viewModel.sizeBytes?.let { sizeText(context, it) }.orEmpty(),
        onDismiss = { askingLarge = false },
        onOpen = {
            askingLarge = false
            viewModel.openExternally()
        },
    )
    OpenProgressDialog(viewModel)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            viewModel.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Subtitle(state, viewModel.dirty, viewModel.saving, viewModel.readOnly)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = leave) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
                    }
                },
                actions = {
                    if (viewModel.saving) {
                        CircularProgressIndicator(Modifier.size(20.dp))
                    } else {
                        TextButton(
                            onClick = viewModel::save,
                            // Nothing to write, or nothing permitted: a button
                            // that would do nothing is worse than no button.
                            enabled = viewModel.dirty && !viewModel.readOnly &&
                                state is State.Ready,
                        ) { Text(stringResource(R.string.save)) }
                    }
                    OpenWithMenu(viewModel, onLarge = { askingLarge = true })
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        // On the Scaffold rather than the field: the confirmation for a save has
        // to be visible above the keyboard, and a snackbar host laid out under
        // it is a message nobody reads.
        modifier = Modifier.imePadding(),
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets)) {
            when (state) {
                State.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is State.Failed -> ErrorState(
                    error = state.error,
                    onRetry = viewModel::retry,
                    onEditServer = null,
                )
                is State.Unreadable -> CenteredMessage(
                    text = stringResource(
                        when (state.reason) {
                            Refusal.BINARY -> R.string.editor_refuse_binary
                            Refusal.TOO_LARGE -> R.string.editor_refuse_large
                            Refusal.UNSUPPORTED -> R.string.editor_refuse_unsupported
                        },
                    ),
                ) {
                    Button(onClick = {
                        if (viewModel.largeDownload) askingLarge = true else viewModel.openExternally()
                    }) { Text(stringResource(R.string.open_with)) }
                }
                is State.Ready -> Field(viewModel)
            }
        }
    }
}

/**
 * The editable text, with a line-number gutter down the left.
 *
 * # Why the scrolling moved out of the field
 *
 * `BasicTextField` scrolls itself, and it does not say where to. A gutter that
 * cannot see the scroll offset cannot follow the text, so the scrolling is done
 * **once, outside both**: the gutter and the field sit side by side inside one
 * `verticalScroll`, and scroll together because they are the same content.
 *
 * # Why the numbers come from the layout, not from counting newlines
 *
 * The field wraps. One logical line can occupy several rows, and the gutter has
 * to put a number on the first row only — counting `\n` would put a number on
 * every line and then drift one row further out of step with every wrap above
 * it. `onTextLayout` hands back the rows the field actually produced, including
 * where each one starts and how far down it sits, and those two facts are all
 * the gutter needs.
 *
 * Only the numbers near the visible part of the text are drawn: a log of a
 * megabyte is tens of thousands of lines, and a draw call each per frame is not
 * something a text field can afford.
 */
@Composable
private fun Field(viewModel: EditorViewModel) {
    val style = MaterialTheme.typography.bodyMedium.copy(
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurface,
    )
    val density = LocalDensity.current
    val scroll = rememberScrollState()
    val measurer = rememberTextMeasurer()
    val numberColor = MaterialTheme.colorScheme.onSurfaceVariant
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var viewport by remember { mutableFloatStateOf(0f) }

    val marks = remember(layout) { layout?.numberMarks(viewModel.text).orEmpty() }
    // Room for the widest number there is, measured in the same font as the text.
    val digits = remember(marks) { marks.size.toString().length.coerceAtLeast(2) }
    val numberWidth = remember(digits, style) { measurer.measure("0".repeat(digits), style).size.width }
    val gap = with(density) { GUTTER_GAP.toPx() }
    val gutter = numberWidth + gap
    val textHeightPx = layout?.size?.height ?: 0

    Box(Modifier.fillMaxSize().onSizeChanged { viewport = it.height.toFloat() }) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(scroll),
        ) {
            Canvas(
                Modifier
                    .width(with(density) { gutter.toDp() })
                    .height(with(density) { textHeightPx.toDp() }),
            ) {
                // One viewport either side of the visible band: generous enough
                // that a fast fling never shows a gap, cheap enough that the
                // cost stays proportional to what is on screen.
                val from = scroll.value - viewport
                val to = scroll.value + viewport * 2
                val numberStyle = style.copy(color = numberColor)
                for ((top, label) in marks) {
                    if (top < from || top > to) continue
                    val width = measurer.measure(label, numberStyle).size.width.toFloat()
                    drawText(
                        measurer,
                        label,
                        topLeft = Offset(gutter - gap - width, top),
                        style = numberStyle,
                    )
                }
            }
            BasicTextField(
                value = viewModel.text,
                onValueChange = viewModel::edit,
                readOnly = viewModel.readOnly,
                textStyle = style,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                onTextLayout = { layout = it },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Where each numbered row starts, and what its number says. */
private fun TextLayoutResult.numberMarks(text: String): List<Pair<Float, String>> {
    val starts = IntArray(lineCount) { getLineStart(it) }
    val numbers = lineNumbers(starts, text)
    val marks = ArrayList<Pair<Float, String>>()
    for (row in 0 until lineCount) {
        val number = numbers[row]
        if (number > 0) marks += getLineTop(row) to number.toString()
    }
    return marks
}

/** Between a number and the text it numbers. */
private val GUTTER_GAP = 12.dp

/** The encoding, and whether the file on screen still matches the file on the server. */
@Composable
private fun Subtitle(state: State, dirty: Boolean, saving: Boolean, readOnly: Boolean) {
    val ready = state as? State.Ready ?: return
    val marker = when {
        saving -> stringResource(R.string.editor_saving)
        dirty -> stringResource(R.string.editor_modified)
        readOnly -> stringResource(R.string.info_read_only)
        else -> null
    }
    val charset = ready.file.charset.name()
    Text(
        if (marker == null) charset else stringResource(R.string.editor_status, charset, marker),
        maxLines = 1,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Hands what is on screen to another app.
 *
 * The action is kept in the overflow rather than beside Save: it is the rarer
 * of the two by a wide margin, and a working editor's second button should not
 * be "give up and let somebody else read this".
 */
@Composable
private fun OpenWithMenu(viewModel: EditorViewModel, onLarge: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.editor_close_menu))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.open_with)) },
                onClick = {
                    open = false
                    if (viewModel.largeDownload) onLarge() else viewModel.openExternally()
                },
            )
        }
    }
}

@Composable
private fun DiscardDialog(
    name: String,
    asking: Boolean,
    onDiscard: () -> Unit,
    onCancel: () -> Unit,
) {
    if (!asking) return
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.editor_discard_title)) },
        text = { Text(stringResource(R.string.editor_discard_body, name)) },
        confirmButton = {
            TextButton(onClick = onDiscard) { Text(stringResource(R.string.editor_discard)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/**
 * What a failed save left on the server.
 *
 * The two shapes are different facts, not different wording: an untouched
 * original needs nothing from the user, while a swapped one leaves two files
 * whose names are the only way back.
 */
@Composable
private fun SaveFailureDialog(viewModel: EditorViewModel) {
    val failure = viewModel.failure ?: return
    val ready = viewModel.state as? State.Ready
    val text = when (failure) {
        SaveFailure.Unmappable -> ready?.let {
            stringResource(R.string.editor_save_unmappable, it.file.charset.name())
        }.orEmpty()
        is SaveFailure.Staged -> when (failure.stage) {
            SaveStage.WRITE -> stringResource(R.string.editor_save_untouched)
            SaveStage.SWAP ->
                stringResource(
                    R.string.editor_save_swapped,
                    failure.temporary,
                    failure.backup.orEmpty(),
                )
        }
    }
    AlertDialog(
        onDismissRequest = viewModel::dismissFailure,
        title = { Text(stringResource(R.string.editor_save_failed_title)) },
        text = {
            Column {
                Text(text)
                // The kernel's own words, when there were any: losing them would
                // make a server-specific refusal undiagnosable.
                (failure as? SaveFailure.Staged)?.reason?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::dismissFailure) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** The one warning a refused file raises on a share: is this download worth it? */
@Composable
private fun LargeOpenDialog(
    asking: Boolean,
    name: String,
    size: String,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
) {
    if (!asking) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.open_large_title)) },
        text = { Text(stringResource(R.string.open_large_message, name, size)) },
        confirmButton = { TextButton(onClick = onOpen) { Text(stringResource(R.string.open_anyway)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun OpenProgressDialog(viewModel: EditorViewModel) {
    val preparing = viewModel.preparing ?: return
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.open_preparing, preparing.name)) },
        text = {
            Column {
                if (preparing.total > 0) {
                    val fraction = (preparing.done.toFloat() / preparing.total).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.open_progress, (fraction * 100).toInt()))
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::cancelOpen) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun NoticeSnackbar(viewModel: EditorViewModel, host: SnackbarHostState) {
    val notice = viewModel.notice ?: return
    val text = stringResource(
        when (notice) {
            Notice.SAVED -> R.string.editor_saved
            Notice.OPEN_FAILED -> R.string.open_failed
            Notice.NOTHING_TO_OPEN -> R.string.editor_nothing_to_open
        },
    )
    LaunchedEffect(notice) {
        host.showSnackbar(text)
        viewModel.dismissNotice()
    }
}
