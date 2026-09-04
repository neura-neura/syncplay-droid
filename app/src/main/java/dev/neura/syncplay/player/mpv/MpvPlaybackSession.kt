package dev.neura.syncplay.player.mpv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Direct libmpv session used by the service, synchronizer and Compose UI.
 *
 * The session deliberately has no playback-framework facade: state is an immutable snapshot and
 * every mutation is a small command on [MpvPlayerEngine]. [events] contains origin-tagged command
 * notifications so remote/correction operations do not echo back into Syncplay as user actions.
 */
@MainThread
class MpvPlaybackSession(
    context: Context,
    private val engine: MpvPlayerEngine = LibMpvEngine(context),
    looper: Looper = Looper.getMainLooper(),
    private val handleAudioFocus: Boolean = true,
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
) : Closeable {
    private val appContext = context.applicationContext
    private val callbackHandler = Handler(looper)
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val closed = AtomicBoolean(false)

    private val _snapshot = MutableStateFlow(MpvPlaybackSnapshot())
    val snapshot: StateFlow<MpvPlaybackSnapshot> = _snapshot.asStateFlow()
    /** Alias for callers that use “state” for the current immutable playback value. */
    val state: StateFlow<MpvPlaybackSnapshot> = snapshot

    private val _events = MutableSharedFlow<MpvPlaybackEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<MpvPlaybackEvent> = _events.asSharedFlow()

    private var mediaUri: Uri? = null
    private var externalSubtitles: List<MpvExternalSubtitle> = emptyList()
    private var audioFocusRequest: AudioFocusRequest? = null
    private var audioFocusHeld = false
    private var audioDucked = false
    private var output: Any? = null
    private var positionAnchorMs = nowMs()
    /** Only an explicit `sub-step` command may update delay from a native callback. */
    private var awaitingAlignedSubtitleDelay = false

    private val audioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
        .build()

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
        callbackHandler.post {
            if (!closed.get()) handleAudioFocusChange(change)
        }
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) return
            callbackHandler.post {
                if (closed.get()) return@post
                abandonAudioFocus()
                updateSnapshot { it.copy(playWhenReady = false, isPlaying = false) }
                engine.pause()
                emitCommand(MpvPlaybackEvent.Kind.PAUSE_REQUESTED, MpvEventOrigin.SYSTEM)
            }
        }
    }

    init {
        engine.setListener { event ->
            if (Looper.myLooper() == looper) handleEngineEvent(event) else callbackHandler.post {
                handleEngineEvent(event)
            }
        }
        registerNoisyReceiver()
    }

    fun setMedia(
        uri: Uri,
        mediaIdentity: String? = uri.toString(),
        title: String? = null,
        externalSubtitles: List<MpvExternalSubtitle> = emptyList(),
        startPositionMs: Long = 0L,
        playWhenReady: Boolean = false,
        origin: MpvEventOrigin = MpvEventOrigin.USER,
    ) {
        checkOpen()
        awaitingAlignedSubtitleDelay = false
        mediaUri = uri
        this.externalSubtitles = externalSubtitles.toList()
        positionAnchorMs = nowMs()
        updateSnapshot {
            MpvPlaybackSnapshot(
                mediaIdentity = mediaIdentity ?: uri.toString(),
                title = title,
                phase = MpvPlaybackPhase.OPENING,
                positionMs = startPositionMs.coerceAtLeast(0L),
                playWhenReady = playWhenReady,
                isPlaying = false,
                rate = it.rate,
                volume = it.volume,
                subtitleDelayMs = it.subtitleDelayMs,
            )
        }
        emitCommand(MpvPlaybackEvent.Kind.MEDIA_REQUESTED, origin)
        runCatching {
            engine.setMedia(uri, this.externalSubtitles, startPositionMs.coerceAtLeast(0L))
            engine.prepare()
            if (playWhenReady) play(origin)
        }.onFailure { error ->
            publishError(error)
        }
    }

    /** Explicitly named alias for service code that opens a URI. */
    fun open(
        uri: Uri,
        mediaIdentity: String? = uri.toString(),
        title: String? = null,
        externalSubtitles: List<MpvExternalSubtitle> = emptyList(),
        startPositionMs: Long = 0L,
        playWhenReady: Boolean = false,
        origin: MpvEventOrigin = MpvEventOrigin.USER,
    ) = setMedia(uri, mediaIdentity, title, externalSubtitles, startPositionMs, playWhenReady, origin)

    fun play(origin: MpvEventOrigin = MpvEventOrigin.USER): Boolean {
        if (closed.get() || mediaUri == null) return false
        if (!requestAudioFocusIfNeeded()) {
            updateSnapshot { it.copy(playWhenReady = false, isPlaying = false) }
            return false
        }
        updateSnapshot { it.copy(playWhenReady = true) }
        engine.play()
        emitCommand(MpvPlaybackEvent.Kind.PLAY_REQUESTED, origin)
        return true
    }

    fun pause(origin: MpvEventOrigin = MpvEventOrigin.USER) {
        if (closed.get()) return
        updateSnapshot { it.copy(playWhenReady = false, isPlaying = false) }
        abandonAudioFocus()
        engine.pause()
        emitCommand(MpvPlaybackEvent.Kind.PAUSE_REQUESTED, origin)
    }

    fun stop(origin: MpvEventOrigin = MpvEventOrigin.USER) {
        if (closed.get()) return
        updateSnapshot { it.copy(phase = MpvPlaybackPhase.STOPPED, playWhenReady = false, isPlaying = false) }
        abandonAudioFocus()
        engine.stop()
        emitCommand(MpvPlaybackEvent.Kind.STOP_REQUESTED, origin)
    }

    fun clear(origin: MpvEventOrigin = MpvEventOrigin.USER) {
        if (closed.get()) return
        awaitingAlignedSubtitleDelay = false
        mediaUri = null
        externalSubtitles = emptyList()
        abandonAudioFocus()
        engine.clearMedia()
        positionAnchorMs = nowMs()
        updateSnapshot { MpvPlaybackSnapshot(volume = it.volume, rate = it.rate) }
        emitCommand(MpvPlaybackEvent.Kind.CLEAR_REQUESTED, origin)
    }

    fun seekTo(
        positionMs: Long,
        exact: Boolean = false,
        origin: MpvEventOrigin = MpvEventOrigin.USER,
    ) {
        if (closed.get() || mediaUri == null) return
        val target = positionMs.coerceAtLeast(0L)
        positionAnchorMs = nowMs()
        updateSnapshot { it.copy(positionMs = target) }
        engine.seekTo(target, exact)
        emitCommand(MpvPlaybackEvent.Kind.SEEK_REQUESTED, origin, seekPositionMs = target)
    }

    fun setRate(rate: Float, origin: MpvEventOrigin = MpvEventOrigin.USER) {
        if (closed.get() || !rate.isFinite()) return
        val value = rate.coerceIn(0.25f, 4f)
        updateSnapshot { it.copy(rate = value) }
        engine.setRate(value)
        emitCommand(MpvPlaybackEvent.Kind.RATE_CHANGED, origin, rate = value)
    }

    fun setVolume(volume: Float, origin: MpvEventOrigin = MpvEventOrigin.USER) {
        if (closed.get() || !volume.isFinite()) return
        val value = volume.coerceIn(0f, 1f)
        updateSnapshot { it.copy(volume = value) }
        engine.setVolume(value)
        emitCommand(MpvPlaybackEvent.Kind.VOLUME_CHANGED, origin, volume = value)
    }

    fun attachOutput(output: Any?, origin: MpvEventOrigin = MpvEventOrigin.SYSTEM) {
        if (closed.get()) return
        if (this.output === output) {
            // A Compose-managed handle keeps its identity while its surface dimensions change.
            if (output is MpvSurfaceOutput) engine.setVideoOutput(output)
            return
        }
        this.output = output
        engine.setVideoOutput(output)
        emitCommand(MpvPlaybackEvent.Kind.STATE_CHANGED, origin)
    }

    fun attachVideoOutput(output: Any?, origin: MpvEventOrigin = MpvEventOrigin.SYSTEM) =
        attachOutput(output, origin)

    fun clearOutput(output: Any? = null, origin: MpvEventOrigin = MpvEventOrigin.SYSTEM) {
        if (closed.get()) return
        if (output != null && this.output !== output) return
        engine.clearVideoOutput(output)
        this.output = null
        emitCommand(MpvPlaybackEvent.Kind.STATE_CHANGED, origin)
    }

    fun clearVideoOutput(output: Any? = null, origin: MpvEventOrigin = MpvEventOrigin.SYSTEM) =
        clearOutput(output, origin)

    fun selectAudioTrack(id: Int, origin: MpvEventOrigin = MpvEventOrigin.USER) {
        if (closed.get()) return
        engine.selectAudioTrack(id)
        emitCommand(MpvPlaybackEvent.Kind.TRACK_SELECTION_CHANGED, origin)
    }

    fun selectVideoTrack(id: Int, origin: MpvEventOrigin = MpvEventOrigin.USER) {
        if (closed.get()) return
        engine.selectVideoTrack(id)
        emitCommand(MpvPlaybackEvent.Kind.TRACK_SELECTION_CHANGED, origin)
    }

    fun selectSubtitleTrack(id: Int, origin: MpvEventOrigin = MpvEventOrigin.USER) {
        if (closed.get()) return
        engine.selectSubtitleTrack(id)
        emitCommand(MpvPlaybackEvent.Kind.TRACK_SELECTION_CHANGED, origin)
    }

    fun replaceExternalSubtitles(
        subtitles: List<MpvExternalSubtitle>,
        origin: MpvEventOrigin = MpvEventOrigin.USER,
    ) {
        if (closed.get()) return
        externalSubtitles = subtitles.toList()
        updateSnapshot { it.copy(subtitleError = null) }
        engine.replaceExternalSubtitles(externalSubtitles)
        emitCommand(MpvPlaybackEvent.Kind.EXTERNAL_SUBTITLES_CHANGED, origin)
    }

    fun applySubtitleAppearance(
        properties: Map<String, Any>,
        origin: MpvEventOrigin = MpvEventOrigin.USER,
    ) {
        if (closed.get()) return
        engine.applySubtitleAppearance(properties)
        emitCommand(MpvPlaybackEvent.Kind.SUBTITLE_APPEARANCE_CHANGED, origin)
    }

    fun setSubtitleDelay(delayMs: Long, origin: MpvEventOrigin = MpvEventOrigin.USER) {
        if (closed.get()) return
        awaitingAlignedSubtitleDelay = false
        updateSnapshot { it.copy(subtitleDelayMs = delayMs) }
        engine.setSubtitleDelay(delayMs)
        emitCommand(MpvPlaybackEvent.Kind.SUBTITLE_DELAY_CHANGED, origin)
    }

    fun alignSubtitleCue(skip: Int, origin: MpvEventOrigin = MpvEventOrigin.USER) {
        if (closed.get() || mediaUri == null) return
        require(skip == -1 || skip == 1) { "Subtitle cue direction must be -1 or 1" }
        val currentTracks = _snapshot.value.tracks
        val selected = currentTracks.tracks.firstOrNull {
            it.type == MpvTrackType.SUBTITLE && it.id == currentTracks.selectedSubtitleId
        }
        if (selected == null || selected.isBitmapSubtitle()) return
        awaitingAlignedSubtitleDelay = true
        engine.alignSubtitleCue(skip)
        emitCommand(MpvPlaybackEvent.Kind.SUBTITLE_TIMING_ALIGNED, origin)
    }

    fun setSubtitleVisibility(visible: Boolean, origin: MpvEventOrigin = MpvEventOrigin.USER) {
        if (closed.get()) return
        engine.setSubtitleVisibility(visible)
        emitCommand(MpvPlaybackEvent.Kind.SUBTITLE_VISIBILITY_CHANGED, origin)
    }

    fun currentPositionMs(): Long = _snapshot.value.positionMs
    fun currentDurationMs(): Long = _snapshot.value.durationMs
    fun isSeekable(): Boolean = _snapshot.value.seekable
    fun hasMedia(): Boolean = mediaUri != null
    fun mediaIdentity(): String? = _snapshot.value.mediaIdentity

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        unregisterNoisyReceiver()
        abandonAudioFocus()
        engine.setListener(null)
        engine.close()
        output = null
        _snapshot.value = MpvPlaybackSnapshot()
    }

    private fun handleEngineEvent(event: MpvEngineEvent) {
        if (closed.get()) return
        val current = _snapshot.value
        val next = when (event.kind) {
            MpvEngineEvent.Kind.MEDIA_CHANGED -> current.copy(
                phase = MpvPlaybackPhase.OPENING,
                positionMs = event.positionMs ?: current.positionMs,
                error = null,
            )
            MpvEngineEvent.Kind.OPENING -> current.copy(
                phase = MpvPlaybackPhase.OPENING,
                positionMs = event.positionMs ?: current.positionMs,
            )
            MpvEngineEvent.Kind.BUFFERING -> current.copy(
                phase = MpvPlaybackPhase.BUFFERING,
                positionMs = event.positionMs ?: current.positionMs,
                bufferedPositionMs = event.bufferingPercent?.let { percent ->
                    if (current.durationMs >= 0L) (current.durationMs * percent / 100f).toLong() else current.bufferedPositionMs
                } ?: current.bufferedPositionMs,
            )
            MpvEngineEvent.Kind.PLAYING -> current.copy(
                phase = MpvPlaybackPhase.PLAYING,
                positionMs = event.positionMs ?: current.positionMs,
                durationMs = event.durationMs ?: current.durationMs,
                playWhenReady = true,
                isPlaying = true,
                seekable = event.seekable ?: current.seekable,
                error = null,
            )
            MpvEngineEvent.Kind.PAUSED -> current.copy(
                phase = MpvPlaybackPhase.PAUSED,
                positionMs = event.positionMs ?: current.positionMs,
                playWhenReady = false,
                isPlaying = false,
            )
            MpvEngineEvent.Kind.STOPPED -> current.copy(
                phase = MpvPlaybackPhase.STOPPED,
                positionMs = event.positionMs ?: current.positionMs,
                playWhenReady = false,
                isPlaying = false,
            )
            MpvEngineEvent.Kind.END_REACHED -> current.copy(
                phase = MpvPlaybackPhase.ENDED,
                positionMs = event.positionMs ?: current.positionMs,
                playWhenReady = false,
                isPlaying = false,
            )
            MpvEngineEvent.Kind.ENCOUNTERED_ERROR -> current.copy(
                phase = MpvPlaybackPhase.ERROR,
                playWhenReady = false,
                isPlaying = false,
                error = event.error,
            )
            MpvEngineEvent.Kind.TIME_CHANGED,
            MpvEngineEvent.Kind.POSITION_CHANGED,
            -> current.copy(positionMs = event.positionMs ?: current.positionMs)
            MpvEngineEvent.Kind.LENGTH_CHANGED -> current.copy(
                durationMs = event.durationMs ?: current.durationMs,
            )
            MpvEngineEvent.Kind.SEEKABLE_CHANGED -> current.copy(
                seekable = event.seekable ?: current.seekable,
            )
            MpvEngineEvent.Kind.VOUT -> current.copy(videoSize = event.videoSize ?: current.videoSize)
            MpvEngineEvent.Kind.SURFACE_SIZE_CHANGED -> current.copy(surfaceSize = event.surfaceSize)
            MpvEngineEvent.Kind.TRACKS_CHANGED -> current.copy(tracks = event.tracks ?: current.tracks)
            MpvEngineEvent.Kind.SUBTITLE_TEXT_CHANGED -> current.copy(subtitleText = event.subtitleText)
            MpvEngineEvent.Kind.SUBTITLE_DELAY_CHANGED -> {
                if (!awaitingAlignedSubtitleDelay || event.subtitleDelayMs == null) return
                awaitingAlignedSubtitleDelay = false
                current.copy(subtitleDelayMs = event.subtitleDelayMs)
            }
            MpvEngineEvent.Kind.EXTERNAL_SUBTITLE_LOAD_FAILED -> current.copy(
                subtitleError = event.error ?: IOException("Unable to load external subtitle"),
            )
        }
        if (event.firstFrameRendered || next != current) {
            positionAnchorMs = nowMs()
            _snapshot.value = next
            _events.tryEmit(
                MpvPlaybackEvent(
                    kind = MpvPlaybackEvent.Kind.STATE_CHANGED,
                    origin = event.origin,
                    snapshot = next,
                    error = event.error,
                ),
            )
        }
    }

    private fun publishError(error: Throwable) {
        val next = _snapshot.value.copy(
            phase = MpvPlaybackPhase.ERROR,
            playWhenReady = false,
            isPlaying = false,
            error = error,
        )
        _snapshot.value = next
        _events.tryEmit(
            MpvPlaybackEvent(
                kind = MpvPlaybackEvent.Kind.STATE_CHANGED,
                origin = MpvEventOrigin.SYSTEM,
                snapshot = next,
                error = error,
            ),
        )
    }

    private fun updateSnapshot(transform: (MpvPlaybackSnapshot) -> MpvPlaybackSnapshot) {
        if (!closed.get()) {
            val next = transform(_snapshot.value)
            _snapshot.value = next
        }
    }

    private fun emitCommand(
        kind: MpvPlaybackEvent.Kind,
        origin: MpvEventOrigin,
        seekPositionMs: Long? = null,
        rate: Float? = null,
        volume: Float? = null,
    ) {
        _events.tryEmit(
            MpvPlaybackEvent(
                kind = kind,
                origin = origin,
                snapshot = _snapshot.value,
                seekPositionMs = seekPositionMs,
                rate = rate,
                volume = volume,
            ),
        )
    }

    private fun requestAudioFocusIfNeeded(): Boolean {
        if (!handleAudioFocus || audioManager == null || audioFocusHeld) return true
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttributes)
                .setOnAudioFocusChangeListener(audioFocusListener, callbackHandler)
                .setWillPauseWhenDucked(false)
                .setAcceptsDelayedFocusGain(true)
                .build()
                .also { audioFocusRequest = it }
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN,
            )
        }
        return when (result) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
                audioFocusHeld = true
                true
            }
            else -> false
        }
    }

    private fun abandonAudioFocus() {
        val manager = audioManager
        if (manager != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { request -> runCatching { manager.abandonAudioFocusRequest(request) } }
            } else if (audioFocusHeld) {
                @Suppress("DEPRECATION")
                runCatching { manager.abandonAudioFocus(audioFocusListener) }
            }
        }
        audioFocusHeld = false
        audioFocusRequest = null
        if (audioDucked) {
            audioDucked = false
            engine.setVolume(_snapshot.value.volume)
        }
    }

    private fun handleAudioFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                audioFocusHeld = true
                if (audioDucked) {
                    audioDucked = false
                    engine.setVolume(_snapshot.value.volume)
                }
                if (_snapshot.value.playWhenReady) engine.play()
            }
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            -> {
                audioFocusHeld = false
                val permanent = change == AudioManager.AUDIOFOCUS_LOSS
                updateSnapshot {
                    it.copy(
                        playWhenReady = if (permanent) false else it.playWhenReady,
                        isPlaying = false,
                    )
                }
                engine.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (!audioDucked) {
                    audioDucked = true
                    engine.setVolume((_snapshot.value.volume * DUCK_VOLUME_MULTIPLIER).coerceIn(0f, 1f))
                }
            }
        }
        emitCommand(MpvPlaybackEvent.Kind.STATE_CHANGED, MpvEventOrigin.SYSTEM)
    }

    private fun registerNoisyReceiver() {
        runCatching {
            ContextCompat.registerReceiver(
                appContext,
                noisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
    }

    private fun unregisterNoisyReceiver() {
        runCatching { appContext.unregisterReceiver(noisyReceiver) }
    }

    private fun checkOpen() {
        check(!closed.get()) { "MpvPlaybackSession is closed" }
    }

    companion object {
        private const val DUCK_VOLUME_MULTIPLIER = 0.2f
    }
}
