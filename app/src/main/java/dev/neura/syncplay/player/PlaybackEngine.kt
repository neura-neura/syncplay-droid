package dev.neura.syncplay.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale

/** Decoder/backend preference exposed by the Media3 session. */
enum class PlaybackEnginePreference {
    AUTOMATIC,
    MEDIA3,
    MPV,
}

/** Concrete player currently attached to the Media3 session. */
enum class PlaybackEngine {
    MEDIA3,
    MPV,
}

enum class PlaybackEngineReason {
    DEFAULT,
    MATROSKA_COMPATIBILITY,
    DIRECT_SMB_COMPATIBILITY,
    SEQUENTIAL_PROVIDER_COMPATIBILITY,
    UNVERIFIED_PROVIDER_COMPATIBILITY,
    USER_SELECTION,
}

data class PlaybackEngineState(
    val preference: PlaybackEnginePreference = PlaybackEnginePreference.AUTOMATIC,
    val active: PlaybackEngine = PlaybackEngine.MEDIA3,
    val reason: PlaybackEngineReason = PlaybackEngineReason.DEFAULT,
)

/**
 * Selects the concrete decoder before a media item is installed.
 *
 * Media3 remains the session/controller contract in every mode. Matroska is routed to the
 * libmpv-backed Media3 Player in automatic mode because vendor HEVC Main10 decoders are commonly
 * associated with stalled seek/playback. The app's direct SMB URI stays on Media3 because the
 * bundled libmpv intentionally has no native SMB/credential bridge; SMB document providers still
 * use MPV through their seekable Android file descriptor.
 */
internal fun selectPlaybackEngine(
    preference: PlaybackEnginePreference,
    displayName: String?,
    mimeType: String?,
    uriPath: String?,
    sourceAccess: SourceAccessClassification = SourceAccessClassification.UNKNOWN,
): Pair<PlaybackEngine, PlaybackEngineReason> = when (preference) {
    PlaybackEnginePreference.MEDIA3 -> PlaybackEngine.MEDIA3 to PlaybackEngineReason.USER_SELECTION
    PlaybackEnginePreference.MPV -> {
        if (sourceAccess == SourceAccessClassification.SEQUENTIAL) {
            PlaybackEngine.MEDIA3 to PlaybackEngineReason.SEQUENTIAL_PROVIDER_COMPATIBILITY
        } else if (uriPath?.startsWith("syncplaysmb:", ignoreCase = true) == true) {
            PlaybackEngine.MEDIA3 to PlaybackEngineReason.DIRECT_SMB_COMPATIBILITY
        } else {
            PlaybackEngine.MPV to PlaybackEngineReason.USER_SELECTION
        }
    }
    PlaybackEnginePreference.AUTOMATIC -> {
        if (sourceAccess == SourceAccessClassification.SEQUENTIAL) {
            PlaybackEngine.MEDIA3 to PlaybackEngineReason.SEQUENTIAL_PROVIDER_COMPATIBILITY
        } else if (uriPath?.startsWith("syncplaysmb:", ignoreCase = true) == true) {
            PlaybackEngine.MEDIA3 to PlaybackEngineReason.DIRECT_SMB_COMPATIBILITY
        } else if (
            uriPath?.startsWith("content:", ignoreCase = true) == true &&
            sourceAccess != SourceAccessClassification.SEEKABLE
        ) {
            // UNKNOWN is deliberately not proof of random access. Users can still explicitly
            // request MPV, but automatic mode leaves an inconclusive provider on Media3.
            PlaybackEngine.MEDIA3 to PlaybackEngineReason.UNVERIFIED_PROVIDER_COMPATIBILITY
        } else if (isMatroskaMedia(displayName, mimeType, uriPath)) {
            PlaybackEngine.MPV to PlaybackEngineReason.MATROSKA_COMPATIBILITY
        } else {
            PlaybackEngine.MEDIA3 to PlaybackEngineReason.DEFAULT
        }
    }
}

internal fun isMatroskaMedia(displayName: String?, mimeType: String?, uriPath: String?): Boolean {
    val normalizedMime = mimeType?.trim()?.lowercase(Locale.ROOT)
    if (normalizedMime == "video/x-matroska" || normalizedMime == "audio/x-matroska") return true

    val candidateName = displayName?.takeIf(String::isNotBlank)
        ?: uriPath?.substringBefore('?')?.substringBefore('#')
        ?: return false
    return candidateName.lowercase(Locale.ROOT).endsWith(".mkv") ||
        candidateName.lowercase(Locale.ROOT).endsWith(".mka")
}

/** Process-local observable state shared by the playback service and Compose UI. */
object PlaybackEngineStore {
    private val _state = MutableStateFlow(PlaybackEngineState())
    val state: StateFlow<PlaybackEngineState> = _state.asStateFlow()

    fun setPreference(preference: PlaybackEnginePreference) {
        _state.update { it.copy(preference = preference) }
    }

    fun setActive(engine: PlaybackEngine, reason: PlaybackEngineReason) {
        _state.update { it.copy(active = engine, reason = reason) }
    }
}
