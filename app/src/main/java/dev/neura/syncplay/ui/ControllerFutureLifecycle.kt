package dev.neura.syncplay.ui

import com.google.common.util.concurrent.ListenableFuture

/**
 * Owns the lifecycle of one asynchronous controller future.
 *
 * A future may complete after its owner has been cleared, or after a newer future replaced it.
 * Keeping the identity and generation check together with the release operation makes it
 * impossible for a stale completion to attach a controller to a closed synchronizer.
 */
internal class ControllerFutureLifecycle<T>(
    private val releaseFuture: (ListenableFuture<T>) -> Unit,
) {
    private data class Registration<T>(
        val future: ListenableFuture<T>,
        val generation: Long,
    )

    private var registration: Registration<T>? = null
    private var nextGeneration = 0L
    private var cleared = false

    /** Register a future, releasing any previous one. Returns null once this owner is cleared. */
    @Synchronized
    fun register(future: ListenableFuture<T>): Long? {
        if (cleared) {
            releaseFuture(future)
            return null
        }

        val previousFuture = registration?.future
        val generation = ++nextGeneration
        registration = Registration(future = future, generation = generation)
        // Publish the replacement before releasing the old future. A release implementation may
        // synchronously run a completion callback, which must already see the old registration as
        // stale.
        previousFuture?.let(releaseFuture)
        return generation
    }

    /** Release the currently owned future and reject all later completions. */
    @Synchronized
    fun clear() {
        if (cleared) return
        cleared = true
        ++nextGeneration
        registration?.future?.let(releaseFuture)
        registration = null
    }

    /**
     * Run [action] only while [future] is still the current, live registration.
     *
     * The check and action are serialized with [register] and [clear], so a lifecycle callback
     * cannot close the owner between the check and a controller attachment.
     */
    @Synchronized
    fun withCurrent(
        future: ListenableFuture<T>,
        generation: Long,
        value: T,
        action: (T) -> Unit,
    ): Boolean {
        val current = registration
        if (cleared || current?.future !== future || current.generation != generation) return false
        action(value)
        return true
    }

    /** Run an action that does not need the completed future's value while it is still current. */
    @Synchronized
    fun withCurrent(
        future: ListenableFuture<T>,
        generation: Long,
        action: () -> Unit,
    ): Boolean {
        val current = registration
        if (cleared || current?.future !== future || current.generation != generation) return false
        action()
        return true
    }
}
