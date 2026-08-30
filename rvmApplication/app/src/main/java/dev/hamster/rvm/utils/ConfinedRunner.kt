package dev.hamster.rvm.utils

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Confines all work for one object to a single dedicated thread.
 *
 * Exists because the objects using it wrap native resources that are not thread-safe, and in
 * TFLite's case are bound to the thread that created them (the GPU delegate's EGL context).
 * Dispatching those objects' work through `Dispatchers.Default` gives no same-thread guarantee,
 * since that dispatcher is a pool.
 *
 * Callers block until the submitted work completes, exactly as they did when the work ran
 * inline - this class changes *which* thread runs the work, not the calling convention.
 */
class ConfinedRunner(threadName: String) {

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, threadName)
    }

    /**
     * Runs [block] on the confined thread and waits for it.
     *
     * Must never be called from the confined thread itself: this is a single-thread executor, so
     * submitting from it and blocking on the result deadlocks. Public entry points wrap; the
     * private implementations they delegate to must call each other directly, unwrapped.
     */
    fun <T> run(block: () -> T): T =
        try {
            executor.submit(Callable { block() }).get()
        } catch (e: ExecutionException) {
            // Unwrap so callers still see the original exception. MatteViewModel surfaces
            // `e.message` straight into the UI's error snackbar, and an un-unwrapped
            // ExecutionException would turn a useful message into a stack-trace class name.
            throw e.cause ?: e
        }

    /** Shuts the thread down. Call from the owner's `close()`; the runner is unusable afterwards. */
    fun shutdown() {
        executor.shutdown()
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
            executor.shutdownNow()
        }
    }
}
