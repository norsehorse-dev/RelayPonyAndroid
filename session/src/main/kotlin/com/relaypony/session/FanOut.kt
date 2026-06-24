package com.relaypony.session

import kotlin.concurrent.thread

/**
 * Runs an action for each target on its own thread, in parallel, isolating failures: one target
 * throwing (a slow or unreachable peer) does not stop the others. [onResult] fires as each target
 * completes, on that target's worker thread, so callers can update per-target UI live. [run]
 * returns only once every target has finished.
 *
 * This is the core of group send: the same files are sent to each selected peer concurrently, each
 * over its own connection and encrypted to its own key.
 */
object FanOut {
    fun <T> run(
        targets: List<T>,
        onResult: (T, Result<Unit>) -> Unit = { _, _ -> },
        action: (T) -> Unit,
    ) {
        val workers = targets.map { target ->
            thread(start = true, name = "relaypony-fanout") {
                val outcome = runCatching { action(target) }
                onResult(target, outcome)
            }
        }
        workers.forEach { it.join() }
    }
}
