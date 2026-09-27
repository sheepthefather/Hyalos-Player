package com.hyalos.player.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A scrollbar for a lazy list: a thin thumb down the right edge that follows the
 * scroll and can be dragged.
 *
 * It appears when the list moves and leaves a moment after it stops, which is
 * what the platform's own lists do — the point is to say "there is more below,
 * and you are this far in" without permanently putting a line over the last
 * column of tiles.
 *
 * Two overloads rather than one taking a common type, because `LazyListState` and
 * `LazyGridState` share no supertype — `LazyListLayoutInfo` and
 * `LazyGridLayoutInfo` are separate interfaces that happen to have the same
 * members. The only differences are where the offset lives (`offset` against
 * `offset.y`) and how they are told where to go; everything past this point is
 * shared.
 */
@Composable
fun ScrollBar(state: LazyListState, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val info = state.layoutInfo
    val first = info.visibleItemsInfo.firstOrNull()
    ScrollBar(
        thumb = scrollThumb(
            total = info.totalItemsCount,
            firstIndex = first?.index ?: 0,
            firstItemProgress = firstItemProgress(first?.offset ?: 0, first?.size ?: 0),
            visibleCount = info.visibleItemsInfo.size,
        ),
        scrolling = state.isScrollInProgress,
        onScrollTo = { index -> scope.launch { state.scrollToItem(index) } },
        modifier = modifier,
    )
}

@Composable
fun ScrollBar(state: LazyGridState, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val info = state.layoutInfo
    val first = info.visibleItemsInfo.firstOrNull()
    ScrollBar(
        thumb = scrollThumb(
            total = info.totalItemsCount,
            firstIndex = first?.index ?: 0,
            firstItemProgress = firstItemProgress(first?.offset?.y ?: 0, first?.size?.height ?: 0),
            visibleCount = info.visibleItemsInfo.size,
        ),
        scrolling = state.isScrollInProgress,
        onScrollTo = { index -> scope.launch { state.scrollToItem(index) } },
        modifier = modifier,
    )
}

/** How long the bar stays after the last movement, then how long it takes to go. */
private const val SCROLLBAR_LINGER_MS = 900L
private const val SCROLLBAR_FADE_MS = 250

/**
 * Wide enough to catch with a thumb, narrow enough to stay off the last column.
 *
 * The *touch* width and the drawn width are deliberately different: a 4dp line
 * is easy to see and impossible to grab.
 */
private val SCROLLBAR_TOUCH_WIDTH = 28.dp
private val SCROLLBAR_WIDTH = 4.dp
private val SCROLLBAR_INSET = 3.dp

/** A thumb shorter than this is neither visible nor grabbable. */
private val SCROLLBAR_MIN_THUMB = 32.dp

@Composable
private fun ScrollBar(
    thumb: ScrollThumb?,
    scrolling: Boolean,
    onScrollTo: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Held separately from `scrolling`: a drag that pauses — a finger resting on
    // the thumb — is not the list scrolling, and the bar must not fade out from
    // under it.
    var held by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf(false) }

    LaunchedEffect(scrolling, held) {
        if (scrolling || held) {
            shown = true
        } else {
            delay(SCROLLBAR_LINGER_MS)
            shown = false
        }
    }

    val barAlpha by animateFloatAsState(
        targetValue = if (shown && thumb != null) 1f else 0f,
        animationSpec = tween(SCROLLBAR_FADE_MS),
        label = "scrollbar",
    )

    BoxWithConstraints(
        modifier.fillMaxHeight().width(SCROLLBAR_TOUCH_WIDTH),
        contentAlignment = Alignment.TopEnd,
    ) {
        if (thumb == null) return@BoxWithConstraints

        val density = LocalDensity.current
        val trackPx = constraints.maxHeight.toFloat()
        val thumbPx = (trackPx * thumb.sizeFraction)
            .coerceAtLeast(with(density) { SCROLLBAR_MIN_THUMB.toPx() })
        val travelPx = (trackPx - thumbPx).coerceAtLeast(0f)

        // Read inside the gesture rather than captured, because the drag must go
        // on using the geometry it started with. Keying `pointerInput` on the
        // thumb instead would restart the detector on every frame of the scroll
        // it is causing, cancelling the very drag in progress.
        val live = rememberUpdatedState(thumb)
        val liveTravel = rememberUpdatedState(travelPx)

        Box(
            Modifier
                .fillMaxHeight()
                .width(SCROLLBAR_TOUCH_WIDTH)
                // Only while it is on screen. A permanently live strip down the
                // right edge would swallow taps meant for the last column of
                // tiles — and a bar that is not visible is not something anyone
                // is trying to grab.
                .then(
                    if (shown) {
                        Modifier.pointerInput(Unit) {
                            var fromPx = 0f
                            var startPx = 0f
                            var draggedPx = 0f
                            var travel = 0f
                            detectDragGestures(
                                onDragStart = {
                                    held = true
                                    travel = liveTravel.value
                                    startPx = travel * live.value.progress
                                    draggedPx = 0f
                                },
                                onDragEnd = { held = false },
                                onDragCancel = { held = false },
                                onDrag = { change, amount ->
                                    change.consume()
                                    draggedPx += amount.y
                                    fromPx = (startPx + draggedPx).coerceIn(0f, travel)
                                    onScrollTo(
                                        scrollIndexAt(
                                            if (travel > 0f) fromPx / travel else 0f,
                                            live.value,
                                        ),
                                    )
                                },
                            )
                        }
                    } else {
                        Modifier
                    },
                )
                .graphicsLayer { alpha = barAlpha },
        ) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = SCROLLBAR_INSET)
                    .width(SCROLLBAR_WIDTH)
                    .height(with(density) { thumbPx.toDp() })
                    .graphicsLayer { translationY = travelPx * thumb.progress }
                    .clip(RoundedCornerShape(SCROLLBAR_WIDTH / 2))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}
