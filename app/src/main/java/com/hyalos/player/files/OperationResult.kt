package com.hyalos.player.files

/**
 * What a bulk file operation actually did.
 *
 * Deliberately not a `Result<Unit>`. A folder of twenty films where three
 * cannot be deleted is neither a success nor a failure, and collapsing it to
 * either would lie to the user about sixteen files: reporting success hides
 * that three are still there, and reporting failure suggests nothing happened.
 * Both are worse than saying "17 done, 3 failed, here is why".
 */
data class OperationResult(
    var succeeded: Int = 0,
    val failures: MutableList<Failure> = mutableListOf(),
    /** Names skipped because something with that name was already there. */
    val conflicts: MutableList<String> = mutableListOf(),
) {
    data class Failure(val path: String, val reason: String)

    /** Whether anything was skipped or failed, and so is worth telling the user about. */
    val hasProblems: Boolean get() = failures.isNotEmpty() || conflicts.isNotEmpty()

    fun recordFailure(path: String, error: Throwable) {
        failures += Failure(path, error.message ?: error::class.simpleName ?: "failed")
    }

    fun recordConflict(path: String) {
        conflicts += path
    }

    /** Fold another result into this one, when several operations run together. */
    fun merge(other: OperationResult) {
        succeeded += other.succeeded
        failures += other.failures
        conflicts += other.conflicts
    }
}
