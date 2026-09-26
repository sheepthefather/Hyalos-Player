package com.hyalos.player.ui.common

import android.content.Context
import android.text.format.DateUtils
import android.text.format.Formatter

/**
 * How a file's size and timestamps are written.
 *
 * Shared because two screens show the same file: the browser in a row's
 * subtitle, the info dialog in a labelled line. Android's own formatting rather
 * than our own — the units and the date order are the device's business.
 */

/** "1.4 GB" */
fun sizeText(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

/** "2024/3/5" */
fun dateText(context: Context, millis: Long): String = DateUtils.formatDateTime(
    context,
    millis,
    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_NUMERIC_DATE,
)
