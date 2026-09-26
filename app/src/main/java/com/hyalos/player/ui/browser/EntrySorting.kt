package com.hyalos.player.ui.browser

import android.icu.text.Collator
import android.icu.text.RuleBasedCollator
import android.icu.util.ULocale
import androidx.annotation.DrawableRes
import com.hyalos.player.R
import com.hyalos.player.data.SortKey
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
    /** Also `null` where the server keeps no such time; SMB servers vary. */
    val createdMs: Long? = null,
    val accessedMs: Long? = null,
    /** The server's own flag. What it means for the current user is another matter. */
    val readOnly: Boolean = false,
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

    /**
     * The system's name order, built once.
     *
     * Building a collator is not free, and the browser and the player need the
     * same one — a playlist that ordered names differently from the listing
     * would play films out of the order they appeared in.
     */
    val systemOrder: Comparator<String> by lazy { systemNameOrder() }

    /**
     * The entries a player would step through, in the order the browser showed
     * them.
     *
     * "Next" has to mean the next one the *user saw*, so this is the same
     * sorting and the same playability rule the listing uses rather than a
     * second opinion about either — a playlist that disagreed with the screen
     * would skip films or play them out of order.
     */
    fun playableInOrder(
        entries: List<DirEntry>,
        key: SortKey = SortKey.NAME,
        ascending: Boolean = true,
        nameOrder: Comparator<String>,
    ): List<BrowserItem> = prepare(entries, key, ascending, nameOrder).filter { it.playable }

    /**
     * Filter, classify and order a raw listing.
     *
     * **Directories stay on top in every order.** Sorting strictly by size would
     * otherwise bury a folder among the large files, and folders are the thing a
     * browser is mostly used to navigate. Each group is ordered independently.
     *
     * Every key falls back to the name order for ties, so a directory of
     * same-sized files (or one where the server reports no dates at all — SMB
     * does this) still comes out in a stable, readable order rather than
     * shuffling between listings.
     */
    fun prepare(
        entries: List<DirEntry>,
        key: SortKey = SortKey.NAME,
        ascending: Boolean = true,
        nameOrder: Comparator<String>,
    ): List<BrowserItem> {
        val byName = Comparator<BrowserItem> { a, b -> nameOrder.compare(a.name, b.name) }
        val byKey: Comparator<BrowserItem> = when (key) {
            SortKey.NAME -> byName
            SortKey.DATE -> tieBreakWithName(byName) { a, b -> absentLast(a.modifiedMs, b.modifiedMs) }
            SortKey.SIZE -> tieBreakWithName(byName) { a, b -> absentLast(a.size, b.size) }
            // Extensions are ASCII, so the locale collator would only add noise.
            SortKey.TYPE -> tieBreakWithName(byName) { a, b -> extensionOf(a.name).compareTo(extensionOf(b.name)) }
        }
        // Reversing the whole comparator flips the tie-break too, which is what
        // "descending" means everywhere else.
        val ordered = if (ascending) byKey else byKey.reversed()

        return entries
            .filterNot { isHidden(it.name) }
            .map { it.toItem() }
            .sortedWith(compareBy<BrowserItem> { it.kind != BrowserItem.Kind.DIRECTORY }.then(ordered))
    }

    /** What "type" sorts by: the extension, without the dot, case-folded. */
    private fun extensionOf(name: String): String =
        name.substringAfterLast('.', "").lowercase()

    private fun tieBreakWithName(
        byName: Comparator<BrowserItem>,
        compare: (BrowserItem, BrowserItem) -> Int,
    ): Comparator<BrowserItem> = Comparator { a, b ->
        compare(a, b).takeIf { it != 0 } ?: byName.compare(a, b)
    }

    /**
     * Compare two optional values, sorting the absent ones **last**.
     *
     * Servers do omit timestamps — SMB reports zero and that becomes `None` — and
     * a directory has no meaningful size. Treating those as zero would scatter
     * them through the list pretending to be from 1970 or to be empty; putting
     * them at the end keeps the real values together.
     */
    private fun <T : Comparable<T>> absentLast(a: T?, b: T?): Int = when {
        a == null && b == null -> 0
        a == null -> 1
        b == null -> -1
        else -> a.compareTo(b)
    }

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
     * The drawable standing in for an entry with no frame to show.
     *
     * Both lists need this — a directory and a playlist draw the same four
     * kinds — so the mapping lives beside the kinds themselves rather than in
     * whichever screen happens to need it first.
     */
    @DrawableRes
    fun iconFor(kind: BrowserItem.Kind): Int = when (kind) {
        BrowserItem.Kind.DIRECTORY -> R.drawable.ic_folder
        BrowserItem.Kind.VIDEO -> R.drawable.ic_movie
        BrowserItem.Kind.AUDIO -> R.drawable.ic_music_note
        BrowserItem.Kind.OTHER -> R.drawable.ic_draft
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
            // Carried along because the listing already brought them: they cost
            // nothing here, whereas asking the server again per file is exactly
            // the round trip `DirEntry` warns against.
            createdMs = metadata.createdMs?.toLong(),
            accessedMs = metadata.accessedMs?.toLong(),
            readOnly = metadata.readOnly,
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
