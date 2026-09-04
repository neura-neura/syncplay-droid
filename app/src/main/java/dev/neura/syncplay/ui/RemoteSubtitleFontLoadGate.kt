package dev.neura.syncplay.ui

/**
 * Identifies the remote subtitle-font load that may still update the current appearance.
 *
 * Cancelling a coroutine does not necessarily interrupt a blocking URL connection. A monotonic
 * request token therefore complements cancellation so a completion that races with a newer URL
 * or font selection cannot apply stale families or an old primary font.
 */
internal class RemoteSubtitleFontLoadGate {
    private var nextGeneration = 0L
    private var activeRequest: Request? = null

    /** Invalidate every in-flight load, for example after an explicit font or URL change. */
    fun invalidate() {
        nextGeneration += 1L
        activeRequest = null
    }

    /** Begin a load for [url], superseding any previous request. */
    fun begin(url: String): Request {
        val request = Request(
            generation = ++nextGeneration,
            url = url,
        )
        activeRequest = request
        return request
    }

    /** Return whether [request] may still update the URL currently shown to the user. */
    fun isCurrent(request: Request, currentUrl: String?): Boolean =
        activeRequest == request && request.url == currentUrl

    data class Request(
        val generation: Long,
        val url: String,
    )
}
