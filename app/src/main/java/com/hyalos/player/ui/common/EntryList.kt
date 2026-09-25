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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hyalos.player.R
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
) {
    LazyColumn(modifier.fillMaxSize()) {
        // Keyed by id, not by name: a playlist may hold the same file name from
        // two folders, and duplicate keys make a lazy list misplace its items.
        items(items, key = { it.id }) { row ->
            val isSelected = row.id in selected
            ListItem(
                modifier = Modifier
                    .combinedClickable(onClick = { onClick(row) }, onLongClick = { onLongClick(row) })
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
                    ),
                leadingContent = {
                    // The check sits over the thumbnail rather than replacing it:
                    // the picture is what tells films apart, and hiding it is
                    // exactly what you do not want while choosing among them.
                    Box {
                        ThumbnailFrame(row, loadThumbnail, Modifier.size(THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT))
                        if (isSelected) SelectedBadge(Modifier.align(Alignment.Center))
                    }
                },
                headlineContent = { Text(row.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                supportingContent = row.detail?.let { { Text(it) } },
            )
        }
    }
}

/**
 * The same entries as tiles of thumbnails.
 *
 * The second line is dropped here — it would crowd a tile — and the frame takes
 * the space instead, which is the point of this view.
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
    LazyVerticalGrid(
        // Adaptive rather than a fixed count: the same code gives two columns on
        // a phone held upright and five on a tablet, without asking the width.
        columns = GridCells.Adaptive(minSize = GRID_MIN_CELL),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier.fillMaxSize(),
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
                    ThumbnailFrame(row, loadThumbnail, Modifier.fillMaxWidth().aspectRatio(THUMBNAIL_ASPECT))
                    if (isSelected) {
                        SelectedBadge(Modifier.align(Alignment.TopEnd).padding(4.dp))
                    }
                }
                Text(
                    text = row.name,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Unspecified,
                    modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
                )
            }
        }
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
) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant, shape),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap by produceState<Bitmap?>(initialValue = null, row.id) {
            val key = row.thumbnail
            value = if (key == null) null else loadThumbnail(key)
        }
        val frame = bitmap
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

/** Narrower would fit three columns of unreadably small tiles on a phone. */
private val GRID_MIN_CELL = 150.dp
