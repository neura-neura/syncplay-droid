package dev.neura.syncplay.ui

/**
 * Identifies the subtitle load that is allowed to update the current media.
 *
 * Subtitle metadata is resolved on a worker dispatcher, so cancelling the coroutine that owns
 * a request is not sufficient for providers that do not interrupt an in-flight read. A monotonic
 * request token makes stale completions harmless: only the most recently started request for the
 * currently selected media may be applied.
 */
internal class SubtitleSelectionGate {
    private var nextGeneration = 0L
    private var activeRequest: Request? = null

    /** Invalidate all pending requests, for example when a new media item is selected. */
    fun invalidate() {
        nextGeneration += 1L
        activeRequest = null
    }

    /** Begin a load for [mediaKey], superseding any previous subtitle load. */
    fun begin(mediaKey: String): Request {
        val request = Request(
            generation = ++nextGeneration,
            mediaKey = mediaKey,
        )
        activeRequest = request
        return request
    }

    /**
     * Return whether [request] can still update the media currently identified by [mediaKey].
     */
    fun isCurrent(request: Request, mediaKey: String?): Boolean =
        activeRequest == request && request.mediaKey == mediaKey

    data class Request(
        val generation: Long,
        val mediaKey: String,
    )
}

/** A subtitle picker selection replaces the active track instead of accumulating tracks. */
internal fun <T> List<T>.replaceWithLatest(value: T): List<T> = listOf(value)
