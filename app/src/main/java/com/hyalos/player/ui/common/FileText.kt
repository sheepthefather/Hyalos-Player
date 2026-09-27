package com.hyalos.player.ui.common

import android.content.Context
import android.text.format.Formatter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * How a file's size and timestamps are written.
 *
 * Shared because two screens show the same file: the browser in a row's
 * subtitle, the info dialog in a labelled line.
 *
 * The size is Android's own formatting — its units are the device's business.
 * The date is not: it is written year first, always.
 */

/** "1.4 GB" */
fun sizeText(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

/**
 * "2026/9/25", year first on every device.
 *
 * `DateUtils.FORMAT_NUMERIC_DATE` would have been the shorter way to write this,
 * but it follows the locale's own order — "9/25/2026" on an en-US device, which
 * is the one thing a list of dates sorted through should not do. The month and
 * day are still unpadded, and the locale is still consulted, so a locale with
 * its own digits gets them.
 *
 * The app's locale, not the system's: Android lets a language be chosen per app,
 * and a date is part of the app's text.
 */
fun dateText(context: Context, millis: Long): String = Instant.ofEpochMilli(millis)
    .atZone(ZoneId.systemDefault())
    .format(
        DateTimeFormatter.ofPattern("yyyy/M/d", context.resources.configuration.locales[0]),
    )
