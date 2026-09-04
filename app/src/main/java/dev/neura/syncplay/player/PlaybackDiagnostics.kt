package dev.neura.syncplay.player

import android.net.Uri
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Privacy-preserving MPV diagnostics shown in the in-app troubleshooting panel. */
data class PlaybackDiagnostics(
    val sourceScheme: String? = null,
    val sourceType: String? = null,
    val videoDecoderName: String? = "MPV",
    val audioDecoderName: String? = "MPV",
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

/** Process-local diagnostic state; callers provide only coarse MPV metadata. */
object PlaybackDiagnosticsStore {
    private val _state = MutableStateFlow(PlaybackDiagnostics())
    val state: StateFlow<PlaybackDiagnostics> = _state.asStateFlow()

    fun restart(uri: Uri?) {
        val source = classifyPlaybackSource(uri)
        _state.value = PlaybackDiagnostics(
            sourceScheme = source?.scheme,
            sourceType = source?.classification,
        )
    }

    fun clear() = restart(null)

    fun update(transform: (PlaybackDiagnostics) -> PlaybackDiagnostics) {
        _state.update(transform)
    }
}

private data class PlaybackSource(val scheme: String, val classification: String)

private fun classifyPlaybackSource(uri: Uri?): PlaybackSource? {
    val scheme = uri?.scheme?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() }
        ?: return uri?.let { PlaybackSource("(sin esquema)", "URI sin esquema") }
    val classification = when (scheme) {
        "content" -> "Proveedor de contenido (SAF)"
        "syncplaysmb" -> "SMB2/3 directo mediante puente MPV"
        "file" -> "Archivo local"
        "http", "https" -> "Streaming HTTP/HTTPS"
        "rtsp" -> "Streaming RTSP"
        "asset" -> "Recurso de la aplicación"
        "android.resource" -> "Recurso de Android"
        else -> "Origen $scheme"
    }
    return PlaybackSource(scheme, classification)
}
