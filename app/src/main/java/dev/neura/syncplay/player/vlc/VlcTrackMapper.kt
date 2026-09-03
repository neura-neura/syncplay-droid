package dev.neura.syncplay.player.vlc

import android.annotation.SuppressLint
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import java.util.Locale

/** Numeric track kinds shared by the libmpv adapter and Media3 facade. */
object VlcTrackType {
    const val UNKNOWN = -1
    const val AUDIO = 0
    const val VIDEO = 1
    const val TEXT = 2
}

/** A small, dependency-light representation of a libmpv elementary stream. */
data class VlcTrackInfo(
    val id: Int,
    val type: Int,
    val codec: String? = null,
    val originalCodec: String? = null,
    val bitrate: Int = Format.NO_VALUE,
    val language: String? = null,
    val description: String? = null,
    val label: String? = null,
    val selectionFlags: Int = 0,
    val roleFlags: Int = 0,
    /** Original MediaItem.SubtitleConfiguration id, when this is a side-loaded subtitle. */
    val externalId: String? = null,
    val width: Int = Format.NO_VALUE,
    val height: Int = Format.NO_VALUE,
    val sampleRate: Int = Format.NO_VALUE,
    val channelCount: Int = Format.NO_VALUE,
    val frameRate: Float = Format.NO_VALUE.toFloat(),
    val pixelWidthHeightRatio: Float = 1f,
    val rotationDegrees: Int = 0,
)

/** Current streams and selected libmpv ids. Kept as a value object for deterministic tests. */
data class VlcTrackSnapshot(
    val tracks: List<VlcTrackInfo> = emptyList(),
    val selectedAudioId: Int = -1,
    val selectedVideoId: Int = -1,
    val selectedTextId: Int = -1,
)

/** Stable id used in Media3 [Format.id] and track-selection overrides. */
internal fun VlcTrackInfo.media3FormatId(): String =
    externalId?.takeIf { it.isNotBlank() } ?: "mpv:$type:$id"

/** Resolve a Media3 format id back to the libmpv stream id. */
internal fun VlcTrackSnapshot.engineTrackId(formatId: String?): Int? {
    if (formatId.isNullOrBlank()) return null
    tracks.firstOrNull { it.media3FormatId() == formatId }?.let { return it.id }
    val parts = formatId.split(':')
    if (parts.size == 3 && parts[0] == "mpv") return parts[2].toIntOrNull()
    return null
}

/**
 * Convert libmpv stream metadata to Media3's immutable [Tracks] representation.
 *
 * libmpv exposes one flat list while Media3 requires every TrackGroup to share language/role
 * metadata. A one-track group is therefore emitted per stream; support is advertised only for
 * codecs for which Media3 can describe the stream. MPV remains
 * the decoder, so this is metadata for controllers and selectors rather than a decoder claim.
 */
@SuppressLint("UnsafeOptInUsageError") // This object is the intentional Media3 metadata adapter.
object VlcTrackMapper {
    @SuppressLint(
        "WrongConstant", // IntArray values are restricted to the C.FORMAT_* branches below.
        "UnsafeOptInUsageError", // Tracks.Group is the intentional Media3 adapter boundary.
    )
    fun toMedia3Tracks(snapshot: VlcTrackSnapshot): Tracks {
        if (snapshot.tracks.isEmpty()) return Tracks.EMPTY

        val groups = snapshot.tracks
            .filter { it.type == VlcTrackType.AUDIO || it.type == VlcTrackType.VIDEO || it.type == VlcTrackType.TEXT }
            .sortedWith(compareBy(VlcTrackInfo::type, VlcTrackInfo::id))
            .map { entry ->
                val format = toFormat(entry)
                val mimeType = mimeTypeFor(entry)
                val support = if (mimeType != null && mimeType != MimeTypes.VIDEO_UNKNOWN &&
                    mimeType != MimeTypes.AUDIO_UNKNOWN && mimeType != MimeTypes.TEXT_UNKNOWN
                ) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_TYPE
                val selectedId = when (entry.type) {
                    VlcTrackType.AUDIO -> snapshot.selectedAudioId
                    VlcTrackType.VIDEO -> snapshot.selectedVideoId
                    else -> snapshot.selectedTextId
                }
                Tracks.Group(
                    TrackGroup("mpv:${typeName(entry.type)}:${entry.id}", format),
                    false,
                    intArrayOf(support),
                    booleanArrayOf(entry.id == selectedId),
                )
            }
        return Tracks(groups)
    }

    private fun toFormat(track: VlcTrackInfo): Format {
        val mimeType = mimeTypeFor(track)
        val label = track.label?.takeIf { it.isNotBlank() }
            ?: track.description?.takeIf { it.isNotBlank() }
        val builder = Format.Builder()
            .setId(track.media3FormatId())
            .setLabel(label)
            .setLanguage(track.language?.takeIf { it.isNotBlank() })
            .setSelectionFlags(track.selectionFlags)
            .setRoleFlags(
                if (track.type == VlcTrackType.TEXT && track.roleFlags == 0) {
                    C.ROLE_FLAG_SUBTITLE
                } else {
                    track.roleFlags
                },
            )
            .setAverageBitrate(track.bitrate)
            .setCodecs(track.codec?.takeIf { it.isNotBlank() })
            .setSampleMimeType(mimeType)
            .setContainerMimeType(mimeType)

        when (track.type) {
            VlcTrackType.VIDEO -> builder
                .setWidth(track.width)
                .setHeight(track.height)
                .setFrameRate(track.frameRate)
                .setRotationDegrees(track.rotationDegrees)
                .setPixelWidthHeightRatio(track.pixelWidthHeightRatio)
            VlcTrackType.AUDIO -> builder
                .setChannelCount(track.channelCount)
                .setSampleRate(track.sampleRate)
        }
        return builder.build()
    }

    private fun mimeTypeFor(track: VlcTrackInfo): String? {
        val codec = track.codec?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return when (track.type) {
            VlcTrackType.VIDEO -> when {
                codec.contains("hevc") || codec.contains("h265") || codec == "hev1" || codec == "hvc1" -> MimeTypes.VIDEO_H265
                codec.contains("avc") || codec.contains("h264") || codec == "avc1" -> MimeTypes.VIDEO_H264
                codec.contains("vp9") || codec == "vp09" -> MimeTypes.VIDEO_VP9
                codec.contains("vp8") || codec == "vp80" -> MimeTypes.VIDEO_VP8
                codec.contains("av1") -> MimeTypes.VIDEO_AV1
                codec.contains("mpeg4") || codec == "mp4v" -> MimeTypes.VIDEO_MP4V
                codec.contains("mpeg2") -> MimeTypes.VIDEO_MPEG2
                else -> MimeTypes.VIDEO_UNKNOWN
            }
            VlcTrackType.AUDIO -> when {
                codec.contains("aac") -> MimeTypes.AUDIO_AAC
                codec.contains("opus") -> MimeTypes.AUDIO_OPUS
                codec.contains("vorbis") -> MimeTypes.AUDIO_VORBIS
                codec.contains("flac") -> MimeTypes.AUDIO_FLAC
                codec.contains("ac-3") || codec.contains("ac3") -> MimeTypes.AUDIO_AC3
                codec.contains("e-ac-3") || codec.contains("eac3") -> MimeTypes.AUDIO_E_AC3
                codec.contains("dts") -> MimeTypes.AUDIO_DTS
                codec.contains("mp3") || codec == "mpga" -> MimeTypes.AUDIO_MPEG
                else -> MimeTypes.AUDIO_UNKNOWN
            }
            VlcTrackType.TEXT -> when {
                codec.contains("webvtt") || codec.contains("wvtt") -> MimeTypes.TEXT_VTT
                codec.contains("ssa") || codec.contains("ass") -> MimeTypes.TEXT_SSA
                codec.contains("subrip") || codec == "srt" -> MimeTypes.APPLICATION_SUBRIP
                codec.contains("pgs") || codec.contains("hdmv") -> MimeTypes.APPLICATION_PGS
                codec.contains("ttml") -> MimeTypes.APPLICATION_TTML
                else -> MimeTypes.TEXT_UNKNOWN
            }
            else -> null
        }
    }

    private fun typeName(type: Int): String = when (type) {
        VlcTrackType.AUDIO -> "audio"
        VlcTrackType.VIDEO -> "video"
        VlcTrackType.TEXT -> "text"
        else -> "unknown"
    }
}
