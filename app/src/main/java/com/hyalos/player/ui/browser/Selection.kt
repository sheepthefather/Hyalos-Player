package com.hyalos.player.ui.browser

/**
 * What is selected in a listing, and whether the browser is in selection mode.
 *
 * Its own type because the two have to agree with each other *and* with the
 * listing, and a selection is a set of **names** — which stop existing when a
 * file is renamed, deleted, or changed by somebody else while the directory is
 * open. Keeping that invariant in one place is what stops the toolbar
 * announcing "1 selected" over a list where nothing looks selected.
 *
 * Pure, so the rules are testable without a screen.
 */
data class Selection(
    val active: Boolean = false,
    val names: Set<String> = emptySet(),
) {
    /** A long press: select this one and enter the mode. */
    fun select(name: String) = Selection(active = true, names = names + name)

    /** A tap while the mode is on. Turning the last one off leaves the mode. */
    fun toggle(name: String): Selection {
        val next = if (name in names) names - name else names + name
        return Selection(active = next.isNotEmpty(), names = next)
    }

    /**
     * Drop names that are not in [present].
     *
     * Called whenever the listing is rebuilt. A name can vanish for reasons the
     * browser did not cause — a rename it just performed, a delete, another
     * client — and a selection holding it would count an item that cannot be
     * seen or acted on.
     */
    fun prune(present: Set<String>): Selection {
        if (names.all { it in present }) return this
        val surviving = names intersect present
        return Selection(active = surviving.isNotEmpty(), names = surviving)
    }

    companion object {
        val NONE = Selection()
    }
}
