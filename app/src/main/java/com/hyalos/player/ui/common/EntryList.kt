package com.hyalos.player.ui.common

import android.graphics.Bitmap
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hyalos.player.R
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import com.hyalos.player.thumbnails.ThumbnailKey

/**
 * One row of an entry list, whichever screen is showing it.
 *
 * A directory and a playlist show the same shape of thing and differ in where
 * each field comes from: a playlist holds only paths, so its second line is the
 * folder rather than a size and a date, and its identity is the path rather than
 * the name. Both are the caller's business — this carries the answer, not the
 * question.
 */
data class EntryRow(
    /** Unique within this list: a name in a directory, a path in a playlist. */
    val id: String,
    val name: String,
    /** The second line, already worded by the caller. `null` keeps the row to one line. */
    val detail: String?,
    /** Drawn when there is no frame to show — a folder, a film, a track. */
    @DrawableRes val icon: Int,
    /** `null` for anything with no frame to extract. */
    val thumbnail: ThumbnailKey?,
)

/**
 * The entries as rows of text.
 *
 * [loadThumbnail] is called only for rows that scroll into view, and cancelled
 * when they leave — see [ThumbnailFrame].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryList(
    items: List<EntryRow>,
    loadThumbnail: suspend (ThumbnailKey) -> Bitmap?,
    selected: Set<String>,
    onClick: (EntryRow) -> Unit,
    onLongClick: (EntryRow) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * How much room the leading slot takes.
     *
     * A thumbnail's by default, because most rows in a file browser have one and
     * the names then line up down the column whether or not a given row does.
     *
     * A list whose rows are **all** icons — the local tab's places — should pass
     * an icon's size instead. A thumbnail's worth of room in front of a picture
     * that is never coming leaves the icon marooned in the middle of an empty
     * slot, which reads as a wide left margin and is what it is.
     *
     * Deliberately a parameter and not something worked out from the rows: a
     * list that re-indented itself depending on whether its contents happened
     * to have thumbnails would put the text in a different place from one
     * directory to the next.
     */
    leadingSize: DpSize = DpSize(THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT),
    /**
     * When given, every row carries a handle and can be dragged to a new place.
     * Called with the two positions once the finger is lifted.
     *
     * Off by default, and only the playlist turns it on. The browser and the
     * local tab draw the same list and have no order to change — theirs is the
     * folder's — so a handle there would be an invitation to rearrange something
     * that has no arrangement.
     *
     * **The handle, not the row.** Long-pressing the row is already how every
     * one of these lists enters its selection mode, and a list where long-press
     * sometimes selects and sometimes picks the row up would be a list nobody
     * could predict.
     */
    onReorder: ((from: Int, to: Int) -> Unit)? = null,
) {
    val state = rememberLazyListState()
    // Created whether or not anything can be dragged, so the list below is one
    // implementation rather than two. With no handle attached it does nothing.
    val reorderState = rememberReorderableLazyListState(state) { from, to ->
        onReorder?.invoke(from.index, to.index)
    }
    Box(modifier.fillMaxSize()) {
        LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
            // Keyed by id, not by name: a playlist may hold the same file name
            // from two folders, and duplicate keys make a lazy list misplace its
            // items.
            items(items, key = { it.id }) { row ->
                val isSelected = row.id in selected
                ReorderableItem(reorderState, key = row.id, enabled = onReorder != null) { _ ->
                ListItem(
                    modifier = Modifier
                        // Only does anything with keys, which this list has. It
                        // is what slides the rows the dragged one passes.
                        .animateItem()
                        .combinedClickable(
                            onClick = { onClick(row) },
                            onLongClick = { onLongClick(row) },
                        )
                        .background(
                            if (isSelected) {
                                MaterialTheme.colorScheme.surfaceVariant
                            } else {
                                Color.Transparent
                            },
                        ),
                    leadingContent = {
                        // The check sits over the thumbnail rather than
                        // replacing it: the picture is what tells films apart,
                        // and hiding it is exactly what you do not want while
                        // choosing among them.
                        Box {
                            ThumbnailFrame(
                                row,
                                loadThumbnail,
                                Modifier.size(leadingSize),
                                // A row with nothing to show shows its icon
                                // bare; the slot is still reserved, so names
                                // still line up down the column.
                                fillWhenEmpty = false,
                            )
                            if (isSelected) SelectedBadge(Modifier.align(Alignment.Center))
                        }
                    },
                    headlineContent = {
                        Text(row.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    },
                    supportingContent = row.detail?.let { { Text(it) } },
                    trailingContent = if (onReorder == null) {
                        null
                    } else {
                        {
                            Icon(
                                painterResource(R.drawable.ic_drag_handle),
                                stringResource(R.string.playlist_reorder_handle),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                // The gesture is on the handle alone, so pressing
                                // the row anywhere else still does what it did.
                                modifier = Modifier
                                    .draggableHandle()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                    },
                )
                }
            }
        }
        ScrollBar(state, Modifier.align(Alignment.CenterEnd))
    }
}

/**
 * The same entries as tiles of thumbnails, with the frame taking the space the
 * list gives to a row's height.
 *
 * The size and the date come along too. They were dropped once, on the
 * reasoning that they would crowd a tile — true at the width of a tile, but a
 * library browsed by picture still wants to know how big a film is and when it
 * was put there, and the answer costs one line of small text.
 */
@Composable
fun EntryGrid(
    items: List<EntryRow>,
    loadThumbnail: suspend (ThumbnailKey) -> Bitmap?,
    selected: Set<String>,
    onClick: (EntryRow) -> Unit,
    onLongClick: (EntryRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = rememberLazyGridState()
    // Three lines of the two styles a tile's text uses: the name gets two, the
    // size and date one. Reserved so that every tile is the same height however
    // the name falls — see where it is applied.
    val typography = MaterialTheme.typography
    val tileTextHeight = with(LocalDensity.current) {
        typography.bodySmall.lineHeight.toDp() * 2 + typography.labelSmall.lineHeight.toDp()
    }
    Box(modifier.fillMaxSize()) {
        LazyVerticalGrid(
            // Adaptive rather than a fixed count: the same code gives three
            // columns on a phone held upright and more on a tablet, without
            // asking the width. See ARCHITECTURE.md for what sets the count.
            columns = GridCells.Adaptive(minSize = GRID_MIN_CELL),
            state = state,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(items, key = { it.id }) { row ->
                val isSelected = row.id in selected
                Column(
                    modifier = Modifier.combinedClickable(
                        onClick = { onClick(row) },
                        onLongClick = { onLongClick(row) },
                    ),
                ) {
                    Box {
                        ThumbnailFrame(
                            row,
                            loadThumbnail,
                            Modifier.fillMaxWidth().aspectRatio(THUMBNAIL_ASPECT),
                        )
                        if (isSelected) {
                            SelectedBadge(Modifier.align(Alignment.TopEnd).padding(4.dp))
                        }
                    }
                    // The name and its size and date, in three lines' worth of
                    // room however they fall: a two-line name puts the detail
                    // on the third line, a one-line name on the second and
                    // leaves the third empty. So tiles stay a uniform height,
                    // and the detail stays against the name it belongs to —
                    // which is why the spare line is kept at the bottom rather
                    // than between them.
                    Column(
                        Modifier
                            .padding(top = 6.dp, start = 2.dp, end = 2.dp)
                            .heightIn(min = tileTextHeight),
                    ) {
                        Text(
                            text = row.name,
                            style = typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                Color.Unspecified
                            },
                        )
                        // One line, and it has to fit: "3.0 MB · 9/25/2026" is
                        // the width of a tile to within a character or two, and
                        // letting it wrap puts the size on one line and the date
                        // on the next — which is not what the line is for.
                        //
                        // `labelSmall`'s letter spacing is what tips it over. It
                        // is 0.5sp per character, meant to keep a short label
                        // legible on its own; over seventeen characters of data
                        // it is ~25px, which is the whole margin. Dropped rather
                        // than the font size, so the text stays 11sp.
                        row.detail?.let {
                            Text(
                                text = it,
                                style = typography.labelSmall.copy(letterSpacing = 0.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
            }
        }
        ScrollBar(state, Modifier.align(Alignment.CenterEnd))
    }
}

/**
 * A video's frame, or an icon for everything else.
 *
 * Shared by both views so the lazy-loading rule lives in one place.
 *
 * [produceState] is what makes extraction lazy: the box composes when its row or
 * tile scrolls into view and is cancelled when it leaves, so no frame is ever
 * pulled for a film nobody is looking at. Its key is the entry's id, so a
 * different list does not reuse the previous one's frames.
 */
@Composable
private fun ThumbnailFrame(
    row: EntryRow,
    loadThumbnail: suspend (ThumbnailKey) -> Bitmap?,
    modifier: Modifier = Modifier,
    /**
     * Whether a row with no picture still gets the filled square.
     *
     * The fill is a **backdrop**: somewhere for a thumbnail to land, and
     * something saying a picture belongs here while it loads. With no picture
     * coming it is a grey square with a small icon lost in the middle of it,
     * which is what a *missing* picture looks like — so the list drops it and
     * draws the icon bare.
     *
     * The grid keeps it, because there the square is not a backdrop but the
     * tile itself; a bare icon would leave the tile with no edges at all.
     */
    fillWhenEmpty: Boolean = true,
) {
    val shape = RoundedCornerShape(4.dp)
    val bitmap by produceState<Bitmap?>(initialValue = null, row.id) {
        val key = row.thumbnail
        value = if (key == null) null else loadThumbnail(key)
    }
    val frame = bitmap
    Box(
        modifier = modifier.then(
            if (fillWhenEmpty || frame != null) {
                Modifier.background(MaterialTheme.colorScheme.surfaceVariant, shape)
            } else {
                Modifier
            },
        ),
        contentAlignment = Alignment.Center,
    ) {
        if (frame == null) {
            Icon(
                painterResource(row.icon),
                null,
                Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Image(
                bitmap = frame.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(shape),
            )
        }
    }
}

/** The tick drawn on a selected row or tile. */
@Composable
private fun SelectedBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(24.dp)
            .background(MaterialTheme.colorScheme.primary, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(R.drawable.ic_check),
            null,
            Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

private val THUMBNAIL_WIDTH = 64.dp
private val THUMBNAIL_HEIGHT = 36.dp
private val THUMBNAIL_ASPECT = 16f / 9f

/**
 * The narrowest a tile may be before the grid drops a column.
 *
 * This one number decides how wide a screen must be to show three across:
 * `Adaptive` asks for `(width - 24 + 12) / (GRID_MIN_CELL + 12)` columns, so the
 * threshold is `3 * (GRID_MIN_CELL + 12) + 12` — 348dp here, just under the
 * 360dp that phones are most often designed to.
 *
 * It was 150.dp, on the reasoning that three columns would be "unreadably
 * small". That is true of text and not of thumbnails. It was then 110.dp, which
 * did not actually deliver the three columns it was changed for: 110 puts the
 * threshold at 378dp, above the commonest phone width there is, so a 360dp phone
 * still showed two. See ARCHITECTURE.md.
 *
 * A tile on a 360dp phone comes out at 104dp — on the 1264x2780, 6.78" screen
 * this was measured against, a 16:9 frame about 2.0 cm wide: big enough to
 * recognise the film, small enough to see a shelf of them at once. (The 121dp a
 * 411dp phone gives is 2.4 cm there; the figure does not carry over, because the
 * same dp is a different length on a different screen.) The price is one more
 * column on wide screens, as before: an 800dp tablet goes from six to seven.
 */
private val GRID_MIN_CELL = 100.dp
