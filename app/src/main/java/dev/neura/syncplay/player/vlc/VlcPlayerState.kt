package dev.neura.syncplay.player.vlc

import androidx.media3.common.Player

/** LibVLC's coarse playback lifecycle, deliberately free of Android classes for unit testing. */
enum class VlcPlaybackPhase {
    IDLE,
    OPENING,
    BUFFERING,
    PLAYING,
    PAUSED,
    STOPPED,
    ENDED,
    ERROR,
}

/** Map LibVLC lifecycle values to the state contract expected by MediaSession and PlayerView. */
internal fun VlcPlaybackPhase.toMedia3PlaybackState(): Int = when (this) {
    VlcPlaybackPhase.OPENING,
    VlcPlaybackPhase.BUFFERING,
    -> Player.STATE_BUFFERING

    VlcPlaybackPhase.PLAYING,
    VlcPlaybackPhase.PAUSED,
    -> Player.STATE_READY

    VlcPlaybackPhase.ENDED -> Player.STATE_ENDED
    VlcPlaybackPhase.IDLE,
    VlcPlaybackPhase.STOPPED,
    VlcPlaybackPhase.ERROR,
    -> Player.STATE_IDLE
}

/** LibVLC reports a playing flag separately from the requested play intent. */
internal fun VlcPlaybackPhase.isPlaying(playWhenReady: Boolean): Boolean =
    playWhenReady && this == VlcPlaybackPhase.PLAYING

/** Keep the requested intent while opening/buffering, but never advertise it after end/error. */
internal fun VlcPlaybackPhase.normalizedPlayWhenReady(requested: Boolean): Boolean = when (this) {
    VlcPlaybackPhase.ENDED,
    VlcPlaybackPhase.ERROR,
    VlcPlaybackPhase.IDLE,
    VlcPlaybackPhase.STOPPED,
    -> false

    else -> requested
}

/** A pure event payload shared by the engine and the Media3 adapter. */
data class VlcEngineEvent(
    val kind: Kind,
    val positionMs: Long? = null,
    val durationMs: Long? = null,
    val bufferingPercent: Float? = null,
    val seekable: Boolean? = null,
    val tracks: VlcTrackSnapshot? = null,
    val videoSize: VlcVideoSize? = null,
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
        TRACKS_CHANGED,
    }
}

data class VlcVideoSize(
    val width: Int,
    val height: Int,
    val pixelWidthHeightRatio: Float = 1f,
    val rotationDegrees: Int = 0,
)
