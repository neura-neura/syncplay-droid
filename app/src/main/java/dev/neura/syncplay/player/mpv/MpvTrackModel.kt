package dev.neura.syncplay.player.mpv

import java.util.Locale

/** Elementary stream kinds exposed by libmpv's track-list property. */
enum class MpvTrackType {
    UNKNOWN,
    AUDIO,
    VIDEO,
    SUBTITLE;

    companion object {
        /** Compatibility spelling for callers that call subtitle streams text tracks. */
        val TEXT: MpvTrackType get() = SUBTITLE
    }
}

/**
 * Dependency-free stream metadata. Numeric values are nullable because libmpv omits fields for
 * some codecs and external sidecars.
 */
data class MpvTrackInfo(
    val id: Int,
    val type: MpvTrackType,
    val codec: String? = null,
    val originalCodec: String? = null,
    val bitrate: Int? = null,
    val language: String? = null,
    val description: String? = null,
    val label: String? = null,
    val isDefault: Boolean = false,
    val isForced: Boolean = false,
    val isHearingImpaired: Boolean = false,
    val isVisualImpaired: Boolean = false,
    val isCommentary: Boolean = false,
    /** Stable id supplied by the caller for an external sidecar. */
    val externalId: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val sampleRate: Int? = null,
    val channelCount: Int? = null,
    val frameRate: Float? = null,
    val pixelWidthHeightRatio: Float = 1f,
    val rotationDegrees: Int = 0,
) {
    /** Stable key suitable for UI state and selection persistence. */
    val stableId: String
        get() = externalId?.takeIf(String::isNotBlank) ?: "mpv:${trackTypeName(type)}:$id"
}

internal fun MpvTrackInfo.isBitmapSubtitle(): Boolean {
    if (type != MpvTrackType.SUBTITLE) return false
    val value = listOfNotNull(codec, originalCodec).joinToString(" ").lowercase(Locale.ROOT)
    return value.contains("pgs") || value.contains("hdmv") || value.contains("dvd_subtitle") ||
        value.contains("dvb_subtitle") || value.contains("vobsub")
}

/** Current streams and selected libmpv ids. */
data class MpvTrackSnapshot(
    val tracks: List<MpvTrackInfo> = emptyList(),
    val selectedAudioId: Int = -1,
    val selectedVideoId: Int = -1,
    val selectedSubtitleId: Int = -1,
) {
    /** Alias for clients that call subtitle streams text tracks. */
    val selectedTextId: Int get() = selectedSubtitleId

    fun selectedId(type: MpvTrackType): Int = when (type) {
        MpvTrackType.AUDIO -> selectedAudioId
        MpvTrackType.VIDEO -> selectedVideoId
        MpvTrackType.SUBTITLE -> selectedSubtitleId
        MpvTrackType.UNKNOWN -> -1
    }

    fun findByStableId(stableId: String?): MpvTrackInfo? =
        stableId?.let { key -> tracks.firstOrNull { it.stableId == key } }
}

/** Resolve a stable UI key back to a native stream id. */
internal fun MpvTrackSnapshot.engineTrackId(stableId: String?): Int? {
    if (stableId.isNullOrBlank()) return null
    tracks.firstOrNull { it.stableId == stableId }?.let { return it.id }
    val parts = stableId.split(':')
    if (parts.size == 3 && parts[0] == "mpv") return parts[2].toIntOrNull()
    return null
}

/** Pure metadata helpers retained for UI and deterministic tests. */
object MpvTrackMapper {
    fun stableId(track: MpvTrackInfo): String = track.stableId

    fun sorted(snapshot: MpvTrackSnapshot): List<MpvTrackInfo> =
        snapshot.tracks.sortedWith(compareBy({ it.type.ordinal }, { it.id }))

    fun mimeTypeFor(track: MpvTrackInfo): String? {
        val codec = track.codec?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return when (track.type) {
            MpvTrackType.VIDEO -> when {
                codec.contains("hevc") || codec.contains("h265") || codec == "hev1" ||
                    codec == "hvc1" -> "video/hevc"
                codec.contains("avc") || codec.contains("h264") || codec == "avc1" ->
                    "video/avc"
                codec.contains("vp9") || codec == "vp09" -> "video/x-vnd.on2.vp9"
                codec.contains("vp8") || codec == "vp80" -> "video/x-vnd.on2.vp8"
                codec.contains("av1") -> "video/av01"
                codec.contains("mpeg4") || codec == "mp4v" -> "video/mp4v-es"
                codec.contains("mpeg2") -> "video/mpeg2"
                else -> null
            }
            MpvTrackType.AUDIO -> when {
                codec.contains("aac") -> "audio/mp4a-latm"
                codec.contains("opus") -> "audio/opus"
                codec.contains("vorbis") -> "audio/vorbis"
                codec.contains("flac") -> "audio/flac"
                codec.contains("ac-3") || codec.contains("ac3") -> "audio/ac3"
                codec.contains("e-ac-3") || codec.contains("eac3") -> "audio/eac3"
                codec.contains("dts") -> "audio/vnd.dts"
                codec.contains("mp3") || codec == "mpga" -> "audio/mpeg"
                else -> null
            }
            MpvTrackType.SUBTITLE -> when {
                codec.contains("webvtt") || codec.contains("wvtt") -> "text/vtt"
                codec.contains("ssa") || codec.contains("ass") -> "text/x-ssa"
                codec.contains("subrip") || codec == "srt" -> "application/x-subrip"
                codec.contains("pgs") || codec.contains("hdmv") -> "application/pgs"
                codec.contains("ttml") -> "application/ttml+xml"
                else -> null
            }
            MpvTrackType.UNKNOWN -> null
        }
    }

    fun typeName(type: MpvTrackType): String = trackTypeName(type)
}

private fun trackTypeName(type: MpvTrackType): String = when (type) {
    MpvTrackType.AUDIO -> "audio"
    MpvTrackType.VIDEO -> "video"
    MpvTrackType.SUBTITLE -> "subtitle"
    MpvTrackType.UNKNOWN -> "unknown"
}
