package dev.neura.syncplay.player

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException
import java.util.Locale
import kotlin.math.max

/**
 * Runtime playback information useful when investigating stutter on a real device.
 *
 * The URI itself is deliberately not part of this state.  A URI can contain a private
 * file name, account, or server path; diagnostics only retain its scheme and a coarse
 * source classification.
 */
data class PlaybackDiagnostics(
    val sourceScheme: String? = null,
    val sourceType: String? = null,
    val videoDecoderName: String? = null,
    val audioDecoderName: String? = null,
    val videoFormat: VideoFormatDiagnostics? = null,
    val audioFormat: AudioFormatDiagnostics? = null,
    val droppedVideoFrames: Int = 0,
    val maxConsecutiveDroppedVideoFrames: Int? = null,
    val videoProcessingOffsetUs: Long = 0L,
    val videoProcessingFrameCount: Int = 0,
    val audioUnderruns: Int = 0,
    val lastAudioUnderrunBufferMs: Long? = null,
    val lastAudioUnderrunSinceFeedMs: Long? = null,
    val bufferingCount: Int = 0,
    val bufferingDurationMs: Long = 0L,
    val isBuffering: Boolean = false,
    val loadErrorCount: Int = 0,
    val lastLoadError: String? = null,
    val networkBitrateEstimateBitsPerSecond: Long? = null,
    val lastBandwidthSampleBytes: Long? = null,
    val playerErrorCount: Int = 0,
    val lastPlayerError: String? = null,
)

data class VideoFormatDiagnostics(
    val sampleMimeType: String? = null,
    val codecs: String? = null,
    /** Codec profile/level token (for example 640028 in avc1.640028), when present. */
    val profile: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val frameRate: Float? = null,
    val bitrate: Int? = null,
    val rotationDegrees: Int? = null,
)

data class AudioFormatDiagnostics(
    val sampleMimeType: String? = null,
    val codecs: String? = null,
    val channelCount: Int? = null,
    val sampleRate: Int? = null,
    val bitrate: Int? = null,
    val language: String? = null,
)

/** Process-local bridge between [PlaybackService] and the Activity's ViewModel. */
object PlaybackDiagnosticsStore {
    private val lock = Any()
    private val _state = MutableStateFlow(PlaybackDiagnostics())
    private var activeMediaKey: String? = null

    val state: StateFlow<PlaybackDiagnostics> = _state.asStateFlow()

    /** Start a new diagnostics sample, even when the user selected the same URI again. */
    fun restart(uri: Uri?) {
        synchronized(lock) {
            activeMediaKey = uri?.toString()
            _state.value = diagnosticsFor(uri)
        }
    }

    /** Ensure a source is present without resetting counters for the current media item. */
    fun beginIfChanged(uri: Uri?): Boolean {
        val key = uri?.toString()
        synchronized(lock) {
            if (key == activeMediaKey) return false
            activeMediaKey = key
            _state.value = diagnosticsFor(uri)
            return true
        }
    }

    fun clear() = restart(null)

    fun update(transform: (PlaybackDiagnostics) -> PlaybackDiagnostics) {
        synchronized(lock) { _state.update(transform) }
    }

    private fun diagnosticsFor(uri: Uri?): PlaybackDiagnostics {
        val source = classifyPlaybackSource(uri)
        return PlaybackDiagnostics(
            sourceScheme = source?.scheme,
            sourceType = source?.classification,
        )
    }
}

private data class PlaybackSource(
    val scheme: String,
    val classification: String,
)

private fun classifyPlaybackSource(uri: Uri?): PlaybackSource? {
    val scheme = uri?.scheme?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() }
        ?: return uri?.let { PlaybackSource("(sin esquema)", "URI sin esquema") }
    val classification = when (scheme) {
        "content" -> "Proveedor de contenido (SAF)"
        "syncplaysmb" -> "SMB2/3 directo (acceso aleatorio)"
        "file" -> "Archivo local"
        "http", "https" -> "Streaming HTTP/HTTPS"
        "rtsp" -> "Streaming RTSP"
        "asset" -> "Recurso de la aplicación"
        "android.resource" -> "Recurso de Android"
        else -> "Origen $scheme"
    }
    return PlaybackSource(scheme, classification)
}

/**
 * Media3 analytics listener attached to the service-owned ExoPlayer.  Analytics callbacks run
 * on the player application looper, while the immutable state can be collected by Compose.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackDiagnosticsCollector(
    private val currentMediaItem: () -> MediaItem?,
) : AnalyticsListener {
    private var bufferingStartedAtRealtimeMs: Long? = null

    override fun onMediaItemTransition(
        eventTime: AnalyticsListener.EventTime,
        mediaItem: MediaItem?,
        reason: Int,
    ) {
        bufferingStartedAtRealtimeMs = null
        PlaybackDiagnosticsStore.restart(mediaItemUri(mediaItem))
        logDebug("media source changed (${sourceLabel()})")
    }

    override fun onPlaybackStateChanged(
        eventTime: AnalyticsListener.EventTime,
        playbackState: Int,
    ) {
        ensureCurrentSource()
        val now = eventTime.realtimeMs.takeIf { it > 0L } ?: SystemClock.elapsedRealtime()
        val wasBuffering = bufferingStartedAtRealtimeMs != null
        var completedDurationMs: Long? = null
        var bufferingCountDelta = 0
        if (playbackState == androidx.media3.common.Player.STATE_BUFFERING) {
            if (!wasBuffering) {
                bufferingStartedAtRealtimeMs = now
                bufferingCountDelta = 1
            }
        } else if (wasBuffering) {
            completedDurationMs = (now - (bufferingStartedAtRealtimeMs ?: now)).coerceAtLeast(0L)
            bufferingStartedAtRealtimeMs = null
        }
        if (bufferingCountDelta != 0 || completedDurationMs != null || wasBuffering !=
            (playbackState == androidx.media3.common.Player.STATE_BUFFERING)
        ) {
            PlaybackDiagnosticsStore.update { current ->
                current.copy(
                    isBuffering = playbackState == androidx.media3.common.Player.STATE_BUFFERING,
                    bufferingCount = current.bufferingCount + bufferingCountDelta,
                    bufferingDurationMs = current.bufferingDurationMs + (completedDurationMs ?: 0L),
                )
            }
            if (bufferingCountDelta != 0) logDebug("buffering started")
            completedDurationMs?.let { duration ->
                logDebug("buffering ended duration=${duration}ms")
            }
        }
    }

    override fun onPlayerError(
        eventTime: AnalyticsListener.EventTime,
        error: PlaybackException,
    ) {
        ensureCurrentSource()
        val summary = summarizeError(error, error.errorCodeName)
        PlaybackDiagnosticsStore.update { current ->
            current.copy(
                playerErrorCount = current.playerErrorCount + 1,
                lastPlayerError = summary,
            )
        }
        Log.w(TAG, "player error code=${error.errorCodeName}: $summary")
    }

    override fun onVideoDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        ensureCurrentSource()
        PlaybackDiagnosticsStore.update { it.copy(videoDecoderName = decoderName.take(MAX_TEXT)) }
        logDebug("video decoder=$decoderName init=${initializationDurationMs}ms")
    }

    override fun onAudioDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        ensureCurrentSource()
        PlaybackDiagnosticsStore.update { it.copy(audioDecoderName = decoderName.take(MAX_TEXT)) }
        logDebug("audio decoder=$decoderName init=${initializationDurationMs}ms")
    }

    override fun onVideoInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?,
    ) {
        ensureCurrentSource()
        val details = videoFormat(format)
        PlaybackDiagnosticsStore.update { it.copy(videoFormat = details) }
        logDebug("video format=${formatForLog(details)}")
    }

    override fun onAudioInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?,
    ) {
        ensureCurrentSource()
        val details = audioFormat(format)
        PlaybackDiagnosticsStore.update { it.copy(audioFormat = details) }
        logDebug("audio format=${formatForLog(details)}")
    }

    override fun onDroppedVideoFrames(
        eventTime: AnalyticsListener.EventTime,
        droppedFrames: Int,
        elapsedMs: Long,
    ) {
        ensureCurrentSource()
        val dropped = droppedFrames.coerceAtLeast(0)
        PlaybackDiagnosticsStore.update { current ->
            current.copy(
                droppedVideoFrames = safeAdd(current.droppedVideoFrames, dropped),
                maxConsecutiveDroppedVideoFrames = max(
                    current.maxConsecutiveDroppedVideoFrames ?: 0,
                    dropped,
                ),
            )
        }
        if (dropped > 0) logDebug("dropped video frames +$dropped (elapsed=${elapsedMs}ms)")
    }

    override fun onVideoFrameProcessingOffset(
        eventTime: AnalyticsListener.EventTime,
        totalProcessingOffsetUs: Long,
        frameCount: Int,
    ) {
        ensureCurrentSource()
        val count = frameCount.coerceAtLeast(0)
        if (count == 0) return
        PlaybackDiagnosticsStore.update { current ->
            current.copy(
                videoProcessingOffsetUs = safeAdd(current.videoProcessingOffsetUs, totalProcessingOffsetUs),
                videoProcessingFrameCount = safeAdd(current.videoProcessingFrameCount, count),
            )
        }
        logDebug("video processing offset total=${totalProcessingOffsetUs}us frames=$count")
    }

    override fun onAudioUnderrun(
        eventTime: AnalyticsListener.EventTime,
        bufferSize: Int,
        bufferSizeMs: Long,
        elapsedSinceLastFeedMs: Long,
    ) {
        ensureCurrentSource()
        PlaybackDiagnosticsStore.update { current ->
            current.copy(
                audioUnderruns = safeAdd(current.audioUnderruns, 1),
                lastAudioUnderrunBufferMs = bufferSizeMs.coerceAtLeast(0L),
                lastAudioUnderrunSinceFeedMs = elapsedSinceLastFeedMs.coerceAtLeast(0L),
            )
        }
        Log.w(TAG, "audio underrun buffer=${bufferSizeMs}ms sinceFeed=${elapsedSinceLastFeedMs}ms")
    }

    override fun onLoadError(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
        error: IOException,
        wasCanceled: Boolean,
    ) {
        ensureCurrentSource()
        val summary = summarizeError(error, "${error.javaClass.simpleName} (tipo=${mediaLoadData.dataType})")
        PlaybackDiagnosticsStore.update { current ->
            current.copy(
                loadErrorCount = current.loadErrorCount + 1,
                lastLoadError = summary,
            )
        }
        Log.w(
            TAG,
            "load error type=${mediaLoadData.dataType} canceled=$wasCanceled " +
                "source=${PlaybackDiagnosticsStore.state.value.sourceType}: $summary",
        )
    }

    override fun onBandwidthEstimate(
        eventTime: AnalyticsListener.EventTime,
        totalLoadTimeMs: Int,
        totalBytesLoaded: Long,
        bitrateEstimate: Long,
    ) {
        ensureCurrentSource()
        PlaybackDiagnosticsStore.update { current ->
            current.copy(
                networkBitrateEstimateBitsPerSecond = bitrateEstimate.takeIf { it > 0L },
                lastBandwidthSampleBytes = totalBytesLoaded.takeIf { it >= 0L },
            )
        }
    }

    override fun onVideoDisabled(
        eventTime: AnalyticsListener.EventTime,
        decoderCounters: DecoderCounters,
    ) {
        // DecoderCounters has the authoritative max-consecutive count once the renderer flushes.
        decoderCounters.ensureUpdated()
        PlaybackDiagnosticsStore.update { current ->
            current.copy(
                droppedVideoFrames = max(current.droppedVideoFrames, decoderCounters.droppedBufferCount),
                maxConsecutiveDroppedVideoFrames = max(
                    current.maxConsecutiveDroppedVideoFrames ?: 0,
                    decoderCounters.maxConsecutiveDroppedBufferCount,
                ),
                videoProcessingOffsetUs = if (
                    decoderCounters.videoFrameProcessingOffsetCount >= current.videoProcessingFrameCount
                ) {
                    decoderCounters.totalVideoFrameProcessingOffsetUs
                } else {
                    current.videoProcessingOffsetUs
                },
                videoProcessingFrameCount = max(
                    current.videoProcessingFrameCount,
                    decoderCounters.videoFrameProcessingOffsetCount,
                ),
            )
        }
    }

    private fun ensureCurrentSource() {
        val item = currentMediaItem() ?: return
        if (PlaybackDiagnosticsStore.beginIfChanged(mediaItemUri(item))) {
            bufferingStartedAtRealtimeMs = null
        }
    }

    private fun mediaItemUri(item: MediaItem?): Uri? = item?.localConfiguration?.uri

    private fun sourceLabel(): String = PlaybackDiagnosticsStore.state.value.sourceType ?: "sin origen"

    private fun videoFormat(format: Format): VideoFormatDiagnostics = VideoFormatDiagnostics(
        sampleMimeType = format.sampleMimeType ?: format.containerMimeType,
        codecs = format.codecs.cleanText(),
        profile = format.codecs?.substringAfter('.', missingDelimiterValue = "")
            ?.takeIf { it.isNotBlank() },
        width = format.width.positiveOrNull(),
        height = format.height.positiveOrNull(),
        frameRate = format.frameRate.takeIf { it.isFinite() && it > 0f },
        bitrate = format.bitrate.positiveOrNull(),
        rotationDegrees = format.rotationDegrees.takeIf { it != Format.NO_VALUE },
    )

    private fun audioFormat(format: Format): AudioFormatDiagnostics = AudioFormatDiagnostics(
        sampleMimeType = format.sampleMimeType ?: format.containerMimeType,
        codecs = format.codecs.cleanText(),
        channelCount = format.channelCount.positiveOrNull(),
        sampleRate = format.sampleRate.positiveOrNull(),
        bitrate = format.bitrate.positiveOrNull(),
        language = format.language.cleanText(),
    )

    private fun formatForLog(format: VideoFormatDiagnostics): String = buildString {
        append(format.sampleMimeType ?: "?")
        format.codecs?.let { append(" codecs=$it") }
        format.width?.let { width -> format.height?.let { height -> append(" ${width}x${height}") } }
    }

    private fun formatForLog(format: AudioFormatDiagnostics): String = buildString {
        append(format.sampleMimeType ?: "?")
        format.codecs?.let { append(" codecs=$it") }
        format.channelCount?.let { append(" ${it}ch") }
        format.sampleRate?.let { append(" ${it}Hz") }
    }

    private fun String?.cleanText(): String? = this?.trim()?.takeIf { it.isNotBlank() }?.take(MAX_TEXT)

    private fun Int.positiveOrNull(): Int? = takeIf { it != Format.NO_VALUE && it > 0 }

    private fun summarizeError(error: Throwable, fallback: String): String {
        val message = error.message
            ?.replace(URI_PATTERN, "<URI>")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        return (message ?: fallback).take(MAX_TEXT)
    }

    private fun logDebug(message: String) = Log.d(TAG, message.take(MAX_TEXT))

    private fun safeAdd(current: Int, delta: Int): Int =
        if (delta > 0 && current > Int.MAX_VALUE - delta) Int.MAX_VALUE else current + delta

    private fun safeAdd(current: Long, delta: Long): Long = when {
        delta > 0L && current > Long.MAX_VALUE - delta -> Long.MAX_VALUE
        delta < 0L && current < Long.MIN_VALUE - delta -> Long.MIN_VALUE
        else -> current + delta
    }

    private companion object {
        const val TAG = "PlaybackDiagnostics"
        const val MAX_TEXT = 180
        val URI_PATTERN = Regex(
            "(?:content|file|https?|rtsp|syncplaysmb)://\\S+",
            RegexOption.IGNORE_CASE,
        )
    }
}
