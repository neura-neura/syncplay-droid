package dev.neura.syncplay.ui

import java.util.Locale

/** Subtitle formats handed directly to MPV/libass or its bitmap subtitle decoders. */
internal enum class SubtitleFileType(
    val extension: String,
    val mimeType: String,
    val isText: Boolean,
) {
    SRT("srt", "application/x-subrip", true),
    ASS("ass", "text/x-ssa", true),
    SSA("ssa", "text/x-ssa", true),
    VTT("vtt", "text/vtt", true),
    TTML("ttml", "application/ttml+xml", true),
    XML("xml", "application/ttml+xml", true),
    ZIP("zip", "application/zip", true),
    SUP("sup", "application/pgs", false),
    PGS("pgs", "application/pgs", false),
}

internal object SubtitleFileTypes {
    private val byExtension = SubtitleFileType.entries.associateBy { it.extension }
    private val byMimeType = SubtitleFileType.entries.groupBy { it.mimeType.lowercase(Locale.ROOT) }
    private val supportedMimeTypes = SubtitleFileType.entries.map(SubtitleFileType::mimeType).toSet()

    fun extensionOf(nameOrUri: String): String? {
        val leaf = nameOrUri
            .substringBefore('#')
            .substringBefore('?')
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .trim()
        val dot = leaf.lastIndexOf('.')
        if (dot <= 0 || dot == leaf.lastIndex) return null
        return leaf.substring(dot + 1).lowercase(Locale.ROOT)
    }

    fun typeForName(nameOrUri: String): SubtitleFileType? =
        extensionOf(nameOrUri)?.let(byExtension::get)

    fun typeFor(nameOrUri: String, providerMime: String?): SubtitleFileType? =
        typeForName(nameOrUri) ?: providerMime
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.let { byMimeType[it]?.firstOrNull() }

    /** Filename wins because SMB/document providers often publish incorrect MIME metadata. */
    fun mimeTypeFor(nameOrUri: String, providerMime: String? = null): String? {
        typeForName(nameOrUri)?.let { return it.mimeType }
        return providerMime
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf(supportedMimeTypes::contains)
    }
}
