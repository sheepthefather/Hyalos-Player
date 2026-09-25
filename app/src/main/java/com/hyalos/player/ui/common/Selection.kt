package com.hyalos.player.ui.common

/**
 * What is selected in a list, and whether the screen is in selection mode.
 *
 * Its own type because the two have to agree with each other *and* with the
 * list, and a selection is a set of **identifiers** — which stop existing when a
 * file is renamed, deleted, or changed by somebody else while the list is open.
 * Keeping that invariant in one place is what stops the toolbar announcing
 * "1 selected" over a list where nothing looks selected.
 *
 * What an identifier *is* belongs to the screen: a directory uses a name (unique
 * within it), a playlist uses a path (names repeat across folders). This type
 * only holds strings and does not care which.
 *
 * Pure, so the rules are testable without a screen.
 */
data class Selection(
    val active: Boolean = false,
    val ids: Set<String> = emptySet(),
) {
    /** A long press: select this one and enter the mode. */
    fun select(id: String) = Selection(active = true, ids = ids + id)

    /** A tap while the mode is on. Turning the last one off leaves the mode. */
    fun toggle(id: String): Selection {
        val next = if (id in ids) ids - id else ids + id
        return Selection(active = next.isNotEmpty(), ids = next)
    }

    /**
     * Drop identifiers that are not in [present].
     *
     * Called whenever the list is rebuilt. An entry can vanish for reasons the
     * screen did not cause — a rename it just performed, a delete, another
     * client — and a selection holding it would count an item that cannot be
     * seen or acted on.
     */
    fun prune(present: Set<String>): Selection {
        if (ids.all { it in present }) return this
        val surviving = ids intersect present
        return Selection(active = surviving.isNotEmpty(), ids = surviving)
    }

    companion object {
        val NONE = Selection()

        /** The select-all action. Nothing to select is not a selection. */
        fun all(ids: Collection<String>): Selection =
            Selection(active = ids.isNotEmpty(), ids = ids.toSet())
    }
}
