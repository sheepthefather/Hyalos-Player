package com.hyalos.player.ui.browser

/**
 * What the search box currently means, and whether a name gets through it.
 *
 * A pure function of the box's contents, so the part worth getting right — what
 * counts as a match, and what a pattern that will not compile does — can be
 * tested without a screen or a server.
 *
 * The search is over **this directory's listing**, which the browser is already
 * holding: it filters what is on screen and asks the server for nothing. That is
 * the same shape as sorting, and for the same reason — the answer is already
 * here, and a round trip per keystroke would be a round trip per keystroke.
 */
sealed interface EntrySearch {

    /** Nothing typed. Everything matches. */
    data object Off : EntrySearch

    /** Plain text, matched anywhere in the name. */
    data class Text(val needle: String) : EntrySearch

    /** A regular expression, matched anywhere in the name. */
    data class Pattern(val regex: Regex) : EntrySearch

    /**
     * A regular expression that does not compile. Nothing matches, and the
     * reason is shown where the list would be — an empty list on its own reads
     * as "this directory has nothing like that", which is a different and wrong
     * answer.
     */
    data class Broken(val reason: String) : EntrySearch

    /** Whether this hides anything at all. */
    val filtering: Boolean get() = this !is Off
}

/**
 * Read the box: [text] as typed, and whether the regular-expression switch is on.
 *
 * A blank box is [EntrySearch.Off] in either mode. Whitespace alone is nothing
 * to match on, and treating " " as a query would hide every file whose name has
 * no space in it.
 *
 * Case is ignored in both modes. This is a file browser, not a text editor:
 * nobody looking for "第01集" means to exclude "第01集" over a capital, and a
 * pattern that wants case back can say `(?-i)`.
 */
internal fun entrySearchOf(text: String, asRegex: Boolean): EntrySearch {
    if (text.isBlank()) return EntrySearch.Off
    if (!asRegex) return EntrySearch.Text(text.trim())
    return try {
        EntrySearch.Pattern(Regex(text, RegexOption.IGNORE_CASE))
    } catch (e: IllegalArgumentException) {
        // PatternSyntaxException's message is a description, the pattern, and a
        // caret line pointing at the fault. The first line is the part that says
        // something; the rest repeats what the user is looking at.
        EntrySearch.Broken(e.message?.substringBefore('\n').orEmpty())
    }
}

/** Whether [name] survives the box. */
internal fun EntrySearch.allows(name: String): Boolean = when (this) {
    EntrySearch.Off -> true
    is EntrySearch.Text -> name.contains(needle, ignoreCase = true)
    is EntrySearch.Pattern -> regex.containsMatchIn(name)
    is EntrySearch.Broken -> false
}
