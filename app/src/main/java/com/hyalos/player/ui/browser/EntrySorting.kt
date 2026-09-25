package com.hyalos.player.ui.browser

import android.icu.text.Collator
import android.icu.text.RuleBasedCollator
import android.icu.util.ULocale
import uniffi.krystallos_ffi.DirEntry
import uniffi.krystallos_ffi.Kind

/** One row in the file browser. */
data class BrowserItem(
    /** Exactly as the server returned it; used verbatim to address the entry. */
    val name: String,
    val kind: Kind,
    /** `null` for directories, where a size means nothing useful. */
    val size: Long?,
    val modifiedMs: Long?,
) {
    enum class Kind { DIRECTORY, VIDEO, AUDIO, OTHER }

    val playable: Boolean get() = kind == Kind.VIDEO || kind == Kind.AUDIO
}

/**
 * Turns a raw listing into what the browser shows: hidden entries dropped,
 * directories first, then by name.
 *
 * The name order is passed in so it can be the system collator in the app and
 * a plain comparator in JVM tests, where `android.icu` does not exist.
 */
object EntrySorting {

    fun prepare(entries: List<DirEntry>, nameOrder: Comparator<String>): List<BrowserItem> =
        entries
            .filterNot { isHidden(it.name) }
            .map { it.toItem() }
            .sortedWith(
                compareBy<BrowserItem> { it.kind != BrowserItem.Kind.DIRECTORY }
                    .thenBy(nameOrder) { it.name },
            )

    /**
     * Dot-files, plus the housekeeping folders Windows and Synology put at the
     * top of every share. None of them hold anything a viewer wants.
     */
    fun isHidden(name: String): Boolean =
        name.startsWith('.') || name.lowercase() in HIDDEN_NAMES

    fun kindOf(name: String): BrowserItem.Kind = when (name.substringAfterLast('.', "").lowercase()) {
        in VIDEO_EXTENSIONS -> BrowserItem.Kind.VIDEO
        in AUDIO_EXTENSIONS -> BrowserItem.Kind.AUDIO
        else -> BrowserItem.Kind.OTHER
    }

    /**
     * Chinese ordering with numbers compared by value, so "第2集" sorts before
     * "第10集". ICU does both; there is no need for a hand-written natural sort.
     * Frozen, which makes it immutable and safe to share.
     */
    fun systemNameOrder(): Comparator<String> {
        val collator = Collator.getInstance(ULocale.SIMPLIFIED_CHINESE) as RuleBasedCollator
        collator.numericCollation = true
        val frozen = collator.freeze()
        return Comparator { a, b -> frozen.compare(a, b) }
    }

    private fun DirEntry.toItem(): BrowserItem {
        val kind = when (metadata.kind) {
            Kind.DIRECTORY -> BrowserItem.Kind.DIRECTORY
            // A link may point at a file; judge it by its name like one. What it
            // resolves to is up to the server when it is opened.
            Kind.FILE, Kind.SYMLINK -> kindOf(name)
            Kind.OTHER -> BrowserItem.Kind.OTHER
        }
        return BrowserItem(
            name = name,
            kind = kind,
            size = metadata.len.toLong().takeIf { kind != BrowserItem.Kind.DIRECTORY },
            modifiedMs = metadata.modifiedMs?.toLong(),
        )
    }

    private val HIDDEN_NAMES = setOf(
        "\$recycle.bin",
        "system volume information",
        "#recycle",
        "@eadir",
        "thumbs.db",
        "desktop.ini",
    )

    /**
     * Containers Media3 can open. WMV and RMVB are left out on purpose: ExoPlayer
     * has no extractor for them, and listing them as playable would only lead
     * to an error screen.
     */
    private val VIDEO_EXTENSIONS = setOf(
        "mp4", "m4v", "mkv", "webm", "mov", "avi", "ts", "m2ts", "mts",
        "flv", "3gp", "mpg", "mpeg",
    )

    private val AUDIO_EXTENSIONS = setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav")
}
