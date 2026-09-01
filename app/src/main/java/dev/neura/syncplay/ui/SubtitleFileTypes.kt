package dev.neura.syncplay.ui

import androidx.media3.common.MimeTypes
import java.util.Locale

/**
 * Subtitle formats that can be handed to Media3 as an external subtitle
 * configuration. File managers and network providers often report a MIME
 * type based on their own database (for example, `.ass` may be reported as
 * `audio/aac`), so the filename extension is the source of truth whenever it
 * is available.
 */
internal enum class SubtitleFileType(
    val extension: String,
    val mediaMimeType: String,
) {
    SRT("srt", MimeTypes.APPLICATION_SUBRIP),
    ASS("ass", MimeTypes.TEXT_SSA),
    SSA("ssa", MimeTypes.TEXT_SSA),
    VTT("vtt", MimeTypes.TEXT_VTT),
    TTML("ttml", MimeTypes.APPLICATION_TTML),
    XML("xml", MimeTypes.APPLICATION_TTML),
}

internal object SubtitleFileTypes {
    private val byExtension = SubtitleFileType.entries.associateBy { it.extension }
    private val supportedMimeTypes = SubtitleFileType.entries
        .map(SubtitleFileType::mediaMimeType)
        .toSet()

    /** Returns the final path extension of a display name or URI-like value. */
    fun extensionOf(nameOrUri: String): String? {
        val withoutFragment = nameOrUri.substringBefore('#')
        val withoutQuery = withoutFragment.substringBefore('?')
        val leaf = withoutQuery
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .trim()
        val dot = leaf.lastIndexOf('.')
        // A leading dot alone is a hidden filename, not a useful extension
        // (e.g. `.ass`), while names such as `.episode.ass` remain valid.
        if (dot <= 0 || dot == leaf.lastIndex) return null
        return leaf.substring(dot + 1).lowercase(Locale.ROOT)
    }

    /** Strict post-selection validation by filename extension. */
    fun typeForName(nameOrUri: String): SubtitleFileType? =
        extensionOf(nameOrUri)?.let(byExtension::get)

    /**
     * Resolves the Media3 MIME type, preferring the extension over provider
     * metadata. Provider MIME is only a fallback for callers that already
     * validated the filename through [typeForName].
     */
    fun mimeTypeFor(nameOrUri: String, providerMime: String? = null): String? {
        typeForName(nameOrUri)?.let { return it.mediaMimeType }
        val normalized = providerMime
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.ROOT)
        return normalized?.takeIf(supportedMimeTypes::contains)
    }
}
