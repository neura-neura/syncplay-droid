package dev.neura.syncplay.player.mpv

/** Coarse lifecycle reported by libmpv. */
enum class MpvPlaybackPhase {
    IDLE,
    OPENING,
    BUFFERING,
    PLAYING,
    PAUSED,
    STOPPED,
    ENDED,
    ERROR,
}

/** Identifies who initiated a playback mutation so Syncplay can suppress command echoes. */
enum class MpvEventOrigin {
    USER,
    REMOTE,
    CORRECTION,
    SYSTEM,
}

/** Native-engine event payload. It intentionally contains no playback-framework types. */
data class MpvEngineEvent(
    val kind: Kind,
    val origin: MpvEventOrigin = MpvEventOrigin.SYSTEM,
    /** True only after libmpv confirms that playback restarted and a frame can be presented. */
    val firstFrameRendered: Boolean = false,
    val positionMs: Long? = null,
    val durationMs: Long? = null,
    val bufferingPercent: Float? = null,
    val seekable: Boolean? = null,
    val tracks: MpvTrackSnapshot? = null,
    val videoSize: MpvVideoSize? = null,
    val surfaceSize: MpvSurfaceSize? = null,
    val subtitleText: String? = null,
    val subtitleDelayMs: Long? = null,
    val error: Throwable? = null,
) {
    enum class Kind {
        MEDIA_CHANGED,
        OPENING,
        BUFFERING,
        PLAYING,
        PAUSED,
        STOPPED,
        END_REACHED,
        ENCOUNTERED_ERROR,
        TIME_CHANGED,
        LENGTH_CHANGED,
        POSITION_CHANGED,
        SEEKABLE_CHANGED,
        VOUT,
        SURFACE_SIZE_CHANGED,
        TRACKS_CHANGED,
        SUBTITLE_TEXT_CHANGED,
        SUBTITLE_DELAY_CHANGED,
        EXTERNAL_SUBTITLE_LOAD_FAILED,
    }
}

/** Value object used by the session, UI and diagnostics. */
data class MpvPlaybackSnapshot(
    val mediaIdentity: String? = null,
    val title: String? = null,
    val phase: MpvPlaybackPhase = MpvPlaybackPhase.IDLE,
    val positionMs: Long = 0L,
    /** -1 means that libmpv has not reported a duration. */
    val durationMs: Long = -1L,
    val bufferedPositionMs: Long = 0L,
    val playWhenReady: Boolean = false,
    val isPlaying: Boolean = false,
    val seekable: Boolean = false,
    val rate: Float = 1f,
    val volume: Float = 1f,
    val tracks: MpvTrackSnapshot = MpvTrackSnapshot(),
    val videoSize: MpvVideoSize? = null,
    val surfaceSize: MpvSurfaceSize? = null,
    val subtitleText: String? = null,
    val subtitleDelayMs: Long = 0L,
    /** A sidecar failure is recoverable and must not put the movie itself into ERROR. */
    val subtitleError: Throwable? = null,
    val error: Throwable? = null,
) {
    /** Convenient alias used by synchronizers that call the stable source key identity. */
    val identity: String? get() = mediaIdentity
}

/** Session-level event. State changes carry the latest immutable snapshot. */
data class MpvPlaybackEvent(
    val kind: Kind,
    val origin: MpvEventOrigin = MpvEventOrigin.SYSTEM,
    val snapshot: MpvPlaybackSnapshot,
    val seekPositionMs: Long? = null,
    val rate: Float? = null,
    val volume: Float? = null,
    val error: Throwable? = null,
) {
    enum class Kind {
        STATE_CHANGED,
        MEDIA_REQUESTED,
        PLAY_REQUESTED,
        PAUSE_REQUESTED,
        STOP_REQUESTED,
        CLEAR_REQUESTED,
        SEEK_REQUESTED,
        RATE_CHANGED,
        VOLUME_CHANGED,
        TRACK_SELECTION_CHANGED,
        EXTERNAL_SUBTITLES_CHANGED,
        SUBTITLE_APPEARANCE_CHANGED,
        SUBTITLE_DELAY_CHANGED,
        SUBTITLE_TIMING_ALIGNED,
        SUBTITLE_VISIBILITY_CHANGED,
    }
}

/** Video dimensions reported by libmpv after stream reconfiguration. */
data class MpvVideoSize(
    val width: Int,
    val height: Int,
    val pixelWidthHeightRatio: Float = 1f,
    val rotationDegrees: Int = 0,
)

/** Current Android output dimensions. */
data class MpvSurfaceSize(val width: Int, val height: Int)

/** Playback-phase helpers kept dependency-free for unit tests. */
internal fun MpvPlaybackPhase.isPlaying(playWhenReady: Boolean): Boolean =
    playWhenReady && this == MpvPlaybackPhase.PLAYING

internal fun MpvPlaybackPhase.normalizedPlayWhenReady(requested: Boolean): Boolean = when (this) {
    MpvPlaybackPhase.ENDED,
    MpvPlaybackPhase.ERROR,
    MpvPlaybackPhase.IDLE,
    MpvPlaybackPhase.STOPPED,
    -> false
    else -> requested
}
