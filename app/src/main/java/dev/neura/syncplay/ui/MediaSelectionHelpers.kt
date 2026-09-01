package dev.neura.syncplay.ui

import java.util.Locale

/** Return a trimmed HTTP(S) URL only when its scheme and host are present. */
internal fun validateHttpMediaUrl(rawUrl: String): String? {
    val value = rawUrl.trim()
    if (value.isEmpty()) return null

    val parsed = runCatching { java.net.URI(value) }.getOrNull() ?: return null
    val scheme = parsed.scheme?.lowercase(Locale.ROOT)
    if (scheme !in HTTP_SCHEMES || parsed.host.isNullOrBlank()) return null
    return value
}

/** Return persisted SAF values that are no longer active selections. */
internal fun <T> stalePersistedUris(
    persistedUris: Iterable<T>,
    activeUris: Set<T>,
): List<T> = persistedUris.filterNot(activeUris::contains).distinct()

/** A stale media load must not finalize state belonging to a newer request. */
internal fun isCurrentMediaLoad(
    requestGeneration: Long,
    currentGeneration: Long,
): Boolean = requestGeneration == currentGeneration

/** Only a snapshot emitted for the installed MediaItem may update UI/file metadata. */
internal fun isProgressForMedia(
    progressMediaIdentity: String?,
    currentMediaIdentity: String?,
): Boolean = progressMediaIdentity == currentMediaIdentity

private val HTTP_SCHEMES = setOf("http", "https")
