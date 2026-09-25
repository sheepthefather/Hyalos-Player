package com.hyalos.player.ui

/**
 * The back stack after jumping to [target] from a breadcrumb.
 *
 * If that directory is already on the stack, everything above it is popped —
 * the same as pressing back that many times, and it keeps that level's cached
 * listing. Otherwise (the browser started below it, from a server's start
 * path) the run of directories on this server is replaced by [target], so
 * back from there leaves the server rather than returning to a deeper level.
 */
fun jumpTo(stack: List<Route>, target: Route.Browse): List<Route> {
    // Copies, never `subList` views: the result is applied back onto `stack`
    // with `replaceWith`, and a view of the list being modified would throw.
    val existing = stack.indexOfLast { it == target }
    if (existing >= 0) return stack.take(existing + 1)

    var end = stack.size
    while (end > 0 && (stack[end - 1] as? Route.Browse)?.serverId == target.serverId) end--
    return stack.take(end) + target
}

/**
 * Make [stack] equal [target], touching only the entries that differ.
 *
 * Replacing the list wholesale would pop and re-push entries that did not
 * change, discarding their ViewModels and state for nothing.
 */
fun <T> MutableList<T>.replaceWith(target: List<T>) {
    var common = 0
    while (common < size && common < target.size && this[common] == target[common]) common++
    while (size > common) removeAt(size - 1)
    addAll(target.subList(common, target.size))
}
