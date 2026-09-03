package dev.neura.syncplay.player.vlc

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.Size
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Media3 [Player] facade backed by libmpv. The facade is intentionally single-item: Syncplay
 * synchronizes one movie at a time, while MediaSession and PlayerView continue to consume the
 * standard Media3 callbacks and state model.
 */
@MainThread
@SuppressLint("UnsafeOptInUsageError") // SimpleBasePlayer is the intended Media3 adapter boundary.
class VlcPlayer(
    context: Context,
    private val engine: VlcPlayerEngine = LibMpvEngine(context),
    looper: Looper = Looper.getMainLooper(),
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
) : SimpleBasePlayer(looper), AutoCloseable {
    private val appContext = context.applicationContext
    private val applicationHandler = Handler(looper)
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val closed = AtomicBoolean(false)
    private var mediaItem: MediaItem? = null
    private var mediaPositionMs = 0L
    private var positionAnchorMs = nowMs()
    private var durationMs = C.TIME_UNSET
    private var bufferedPositionMs = 0L
    private var phase = VlcPlaybackPhase.IDLE
    private var playWhenReady = false
    private var playWhenReadyReason = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
    private var playbackSuppressionReason = Player.PLAYBACK_SUPPRESSION_REASON_NONE
    private var playerError: PlaybackException? = null
    private var isLoading = false
    private var seekable = false
    private var trackSnapshot = VlcTrackSnapshot()
    private var tracks: Tracks = Tracks.EMPTY
    private var trackSelectionParameters = TrackSelectionParameters.DEFAULT
    private var playbackParameters = PlaybackParameters.DEFAULT
    private var audioAttributes = AudioAttributes.DEFAULT
    private var handleAudioFocus = false
    private var volume = 1f
    private var videoSize = VideoSize.UNKNOWN
    private var surfaceSize = Size.UNKNOWN
    /** One-shot event that removes PlayerView's shutter over the native MPV surface. */
    private var newlyRenderedFirstFrame = false
    private var pendingDiscontinuity: Pair<Int, Long>? = null
    private var surfaceOutput: Any? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var audioFocusHeld = false
    private var audioDucked = false

    private enum class AudioFocusResult {
        GRANTED,
        DELAYED,
        FAILED,
    }

    /** Public Android audio-focus callback; no Media3-internal focus classes are required. */
    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
        applicationHandler.post {
            if (!closed.get()) handleAudioFocusChange(change)
        }
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) return
            applicationHandler.post {
                if (closed.get()) return@post
                playWhenReady = false
                playWhenReadyReason = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY
                playbackSuppressionReason = Player.PLAYBACK_SUPPRESSION_REASON_NONE
                abandonAudioFocus()
                engine.pause()
                invalidateState()
            }
        }
    }

    init {
        engine.setListener { event ->
            if (Looper.myLooper() == looper) handleEngineEvent(event) else applicationHandler.post {
                handleEngineEvent(event)
            }
        }
        registerNoisyReceiver()
    }

    override fun getState(): State {
        val currentPosition = currentPositionForState()
        val currentItem = mediaItem
        val itemData = currentItem?.let { item ->
            val itemDurationUs = durationMsToUs(durationMs)
            SimpleBasePlayer.MediaItemData.Builder(item.mediaId.ifBlank { item.localConfiguration?.uri.toString() })
                .setMediaItem(item)
                .setMediaMetadata(item.mediaMetadata)
                .setTracks(tracks)
                .setIsSeekable(seekable)
                .setIsDynamic(false)
                .setDurationUs(itemDurationUs)
                .setPeriods(
                    listOf(
                        SimpleBasePlayer.PeriodData.Builder("${item.mediaId}:period")
                            .setDurationUs(itemDurationUs)
                            .build(),
                    ),
                )
                .build()
        }
        val builder = State.Builder()
            .setAvailableCommands(AVAILABLE_COMMANDS)
            .setPlayWhenReady(playWhenReady, playWhenReadyReason)
            .setPlaybackState(phase.toMedia3PlaybackState())
            .setPlaybackSuppressionReason(playbackSuppressionReason)
            .setPlayerError(playerError)
            .setIsLoading(isLoading)
            .setPlaybackParameters(playbackParameters)
            .setTrackSelectionParameters(trackSelectionParameters)
            .setAudioAttributes(audioAttributes)
            .setAudioSessionId(C.AUDIO_SESSION_ID_UNSET)
            .setVolume(volume)
            .setUnmuteVolume(volume)
            .setVideoSize(videoSize)
            .setNewlyRenderedFirstFrame(newlyRenderedFirstFrame)
            .setCurrentCues(CueGroup.EMPTY_TIME_ZERO)
            .setSurfaceSize(surfaceSize)
            .setPlaylistMetadata(currentItem?.mediaMetadata ?: MediaMetadata.EMPTY)
            .setContentPositionMs(currentPosition)
            .setContentBufferedPositionMs(SimpleBasePlayer.PositionSupplier.getConstant(bufferedPositionMs))
            .setTotalBufferedDurationMs(
                SimpleBasePlayer.PositionSupplier.getConstant(
                    (bufferedPositionMs - currentPosition).coerceAtLeast(0L),
                ),
            )

        if (itemData == null) {
            builder.setPlaylist(emptyList())
        } else {
            builder.setPlaylist(listOf(itemData))
                .setCurrentMediaItemIndex(0)
        }
        pendingDiscontinuity?.let { (reason, position) ->
            builder.setPositionDiscontinuity(reason, position)
            pendingDiscontinuity = null
        }
        newlyRenderedFirstFrame = false
        return builder.build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        this.playWhenReady = playWhenReady
        playWhenReadyReason = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
        if (!playWhenReady) {
            playbackSuppressionReason = Player.PLAYBACK_SUPPRESSION_REASON_NONE
            abandonAudioFocus()
            engine.pause()
        } else {
            when (requestAudioFocusIfNeeded()) {
                AudioFocusResult.GRANTED -> {
                    playbackSuppressionReason = Player.PLAYBACK_SUPPRESSION_REASON_NONE
                    if (mediaItem != null) engine.play()
                }
                AudioFocusResult.DELAYED -> {
                    playbackSuppressionReason =
                        Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS
                    engine.pause()
                }
                AudioFocusResult.FAILED -> {
                    this.playWhenReady = false
                    playWhenReadyReason = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS
                    playbackSuppressionReason = Player.PLAYBACK_SUPPRESSION_REASON_NONE
                    engine.pause()
                }
            }
        }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        if (mediaItem == null) return Futures.immediateVoidFuture()
        phase = VlcPlaybackPhase.OPENING
        isLoading = true
        playerError = null
        engine.prepare()
        if (playWhenReady && playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE) {
            engine.play()
        }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        engine.stop()
        abandonAudioFocus()
        phase = VlcPlaybackPhase.IDLE
        isLoading = false
        playWhenReady = false
        playbackSuppressionReason = Player.PLAYBACK_SUPPRESSION_REASON_NONE
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        if (closed.compareAndSet(false, true)) {
            unregisterNoisyReceiver()
            abandonAudioFocus()
            engine.setListener(null)
            engine.close()
            phase = VlcPlaybackPhase.IDLE
            isLoading = false
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        this.playbackParameters = playbackParameters
        anchorPosition()
        engine.setRate(playbackParameters.speed)
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if (mediaItemIndex != 0 || mediaItem == null) return Futures.immediateVoidFuture()
        val target = if (positionMs == C.TIME_UNSET) 0L else positionMs.coerceAtLeast(0L)
        mediaPositionMs = target
        positionAnchorMs = nowMs()
        pendingDiscontinuity = Player.DISCONTINUITY_REASON_SEEK to target
        engine.seekTo(target)
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> {
        if (mediaItems.isEmpty()) {
            clearMediaInternal()
            return Futures.immediateVoidFuture()
        }
        if (mediaItems.size != 1 || startIndex != 0) {
            return Futures.immediateFailedFuture<Any?>(
                IllegalArgumentException("VlcPlayer supports exactly one MediaItem"),
            )
        }
        val item = mediaItems.single()
        val uri = item.localConfiguration?.uri
            ?: return Futures.immediateFailedFuture<Any?>(IllegalArgumentException("MediaItem has no URI"))
        val externalSubtitles = item.localConfiguration?.subtitleConfigurations.orEmpty().map {
            VlcExternalSubtitle(
                id = it.id,
                uri = it.uri,
                language = it.language,
                label = it.label,
                mimeType = it.mimeType,
                selectionFlags = it.selectionFlags,
            )
        }
        mediaItem = item
        mediaPositionMs = startPositionMs.takeUnless { it == C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L
        positionAnchorMs = nowMs()
        durationMs = C.TIME_UNSET
        bufferedPositionMs = 0L
        phase = VlcPlaybackPhase.IDLE
        isLoading = false
        seekable = false
        playerError = null
        trackSnapshot = VlcTrackSnapshot()
        tracks = Tracks.EMPTY
        videoSize = VideoSize.UNKNOWN
        surfaceSize = Size.UNKNOWN
        newlyRenderedFirstFrame = false
        runCatching { engine.setMedia(uri, externalSubtitles, mediaPositionMs) }
            .onFailure { error ->
                phase = VlcPlaybackPhase.ERROR
                playerError = PlaybackException(
                    "Unable to open media with MPV",
                    error,
                    PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                )
            }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleAddMediaItems(index: Int, mediaItems: List<MediaItem>): ListenableFuture<*> =
        if (mediaItem == null && index == 0 && mediaItems.size == 1) {
            handleSetMediaItems(mediaItems, 0, C.TIME_UNSET)
        } else {
            Futures.immediateFailedFuture<Any?>(IllegalArgumentException("VlcPlayer is single-item"))
        }

    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> =
        if (mediaItem == null || (fromIndex == 0 && toIndex == 1 && newIndex == 0)) {
            Futures.immediateVoidFuture()
        } else {
            Futures.immediateFailedFuture<Any?>(IllegalArgumentException("VlcPlayer is single-item"))
        }

    override fun handleReplaceMediaItems(
        fromIndex: Int,
        toIndex: Int,
        mediaItems: List<MediaItem>,
    ): ListenableFuture<*> {
        if (fromIndex != 0 || toIndex !in 0..1 || (toIndex == 0 && mediaItems.isNotEmpty())) {
            return Futures.immediateFailedFuture<Any?>(IllegalArgumentException("Invalid single-item range"))
        }
        return if (mediaItems.isEmpty()) {
            clearMediaInternal()
            Futures.immediateVoidFuture()
        } else if (mediaItems.size == 1) {
            handleSetMediaItems(mediaItems, 0, C.TIME_UNSET)
        } else {
            Futures.immediateFailedFuture<Any?>(IllegalArgumentException("VlcPlayer is single-item"))
        }
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        if (fromIndex == 0 && toIndex == 1) clearMediaInternal()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetTrackSelectionParameters(
        parameters: TrackSelectionParameters,
    ): ListenableFuture<*> {
        trackSelectionParameters = parameters
        applyTrackSelectionParameters()
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION") // SimpleBasePlayer's legacy volume hook.
    override fun handleSetVolume(volume: Float): ListenableFuture<*> {
        this.volume = volume.coerceIn(0f, 1f)
        engine.setVolume(this.volume)
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    @Suppress(
        "DEPRECATION",
        "OVERRIDE_DEPRECATION",
    ) // Media3 1.11 still dispatches this Player command to custom players.
    override fun handleSetAudioAttributes(
        audioAttributes: AudioAttributes,
        handleAudioFocus: Boolean,
    ): ListenableFuture<*> {
        abandonAudioFocus()
        this.audioAttributes = audioAttributes
        this.handleAudioFocus = handleAudioFocus
        if (handleAudioFocus && playWhenReady) {
            when (requestAudioFocusIfNeeded()) {
                AudioFocusResult.GRANTED -> if (mediaItem != null) engine.play()
                AudioFocusResult.DELAYED -> playbackSuppressionReason =
                    Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS
                AudioFocusResult.FAILED -> {
                    playWhenReady = false
                    playWhenReadyReason = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS
                    playbackSuppressionReason = Player.PLAYBACK_SUPPRESSION_REASON_NONE
                }
            }
        }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetVideoOutput(videoOutput: Any): ListenableFuture<*> {
        surfaceOutput = videoOutput
        engine.setVideoOutput(videoOutput)
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleClearVideoOutput(videoOutput: Any?): ListenableFuture<*> {
        engine.clearVideoOutput(videoOutput)
        if (videoOutput == null || surfaceOutput === videoOutput) surfaceOutput = null
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun close() {
        release()
    }

    private fun handleEngineEvent(event: VlcEngineEvent) {
        if (closed.get()) return
        // stop/clear/loadfile are asynchronous in libmpv. Ignore callbacks belonging to the
        // item that was just cleared; SimpleBasePlayer forbids READY/BUFFERING with no playlist.
        if (
            mediaItem == null &&
            event.kind != VlcEngineEvent.Kind.SURFACE_SIZE_CHANGED
        ) {
            return
        }
        event.positionMs?.let {
            mediaPositionMs = it.coerceAtLeast(0L)
            positionAnchorMs = nowMs()
        }
        event.durationMs?.takeIf { it >= 0L }?.let { durationMs = it }
        event.seekable?.let { seekable = it }
        event.tracks?.let {
            trackSnapshot = it
            tracks = VlcTrackMapper.toMedia3Tracks(it)
            applyTrackSelectionParameters()
        }
        event.videoSize?.let {
            if (it.width > 0 && it.height > 0) {
                // MPV applies stream rotation before rendering; Media3 only needs the displayed
                // dimensions and pixel ratio. Avoid the deprecated unapplied-rotation constructor.
                videoSize = VideoSize(it.width, it.height, it.pixelWidthHeightRatio)
            }
        }
        event.surfaceSize?.let {
            surfaceSize = if (it.width > 0 && it.height > 0) Size(it.width, it.height) else Size.UNKNOWN
        }
        if (event.firstFrameRendered && trackSnapshot.tracks.any { it.type == VlcTrackType.VIDEO }) {
            newlyRenderedFirstFrame = true
        }
        when (event.kind) {
            VlcEngineEvent.Kind.MEDIA_CHANGED -> {
                phase = VlcPlaybackPhase.OPENING
                isLoading = true
            }
            VlcEngineEvent.Kind.OPENING,
            VlcEngineEvent.Kind.BUFFERING,
            -> {
                phase = if (event.kind == VlcEngineEvent.Kind.BUFFERING) {
                    VlcPlaybackPhase.BUFFERING
                } else {
                    VlcPlaybackPhase.OPENING
                }
                isLoading = true
                event.bufferingPercent?.let { percent ->
                    if (durationMs >= 0L) bufferedPositionMs = (durationMs * percent / 100f).toLong()
                }
            }
            VlcEngineEvent.Kind.PLAYING -> {
                phase = VlcPlaybackPhase.PLAYING
                isLoading = false
                playbackSuppressionReason = Player.PLAYBACK_SUPPRESSION_REASON_NONE
            }
            VlcEngineEvent.Kind.PAUSED -> {
                anchorPosition()
                phase = VlcPlaybackPhase.PAUSED
                isLoading = false
            }
            VlcEngineEvent.Kind.STOPPED -> {
                abandonAudioFocus()
                phase = VlcPlaybackPhase.IDLE
                isLoading = false
            }
            VlcEngineEvent.Kind.END_REACHED -> {
                abandonAudioFocus()
                phase = VlcPlaybackPhase.ENDED
                isLoading = false
                playWhenReady = false
                playWhenReadyReason = Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM
            }
            VlcEngineEvent.Kind.ENCOUNTERED_ERROR -> {
                abandonAudioFocus()
                phase = VlcPlaybackPhase.ERROR
                isLoading = false
                playWhenReady = false
                playerError = PlaybackException(
                    "MPV playback failed",
                    event.error,
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                )
            }
            VlcEngineEvent.Kind.TIME_CHANGED,
            VlcEngineEvent.Kind.POSITION_CHANGED,
            VlcEngineEvent.Kind.LENGTH_CHANGED,
            VlcEngineEvent.Kind.SEEKABLE_CHANGED,
            VlcEngineEvent.Kind.VOUT,
            VlcEngineEvent.Kind.SURFACE_SIZE_CHANGED,
            -> Unit
            VlcEngineEvent.Kind.TRACKS_CHANGED -> {
                // MPV has no Media3 prepared callback. A successful track list is the
                // paused/ready state expected by MediaSession and PlayerView.
                if (!playWhenReady && trackSnapshot.tracks.isNotEmpty() &&
                    phase in setOf(VlcPlaybackPhase.OPENING, VlcPlaybackPhase.BUFFERING)
                ) {
                    phase = VlcPlaybackPhase.PAUSED
                    isLoading = false
                }
            }
        }
        invalidateState()
    }

    private fun currentPositionForState(): Long {
        if (phase == VlcPlaybackPhase.PLAYING && playWhenReady) {
            val elapsed = (nowMs() - positionAnchorMs).coerceAtLeast(0L)
            val advanced = (elapsed * playbackParameters.speed).toLong()
            return (mediaPositionMs + advanced).coerceAtMost(durationMs.takeIf { it >= 0L } ?: Long.MAX_VALUE)
        }
        return mediaPositionMs
    }

    private fun anchorPosition() {
        mediaPositionMs = currentPositionForState()
        positionAnchorMs = nowMs()
    }

    private fun clearMediaInternal() {
        engine.clearMedia()
        abandonAudioFocus()
        mediaItem = null
        mediaPositionMs = 0L
        durationMs = C.TIME_UNSET
        bufferedPositionMs = 0L
        phase = VlcPlaybackPhase.IDLE
        isLoading = false
        seekable = false
        playWhenReady = false
        playerError = null
        tracks = Tracks.EMPTY
        trackSnapshot = VlcTrackSnapshot()
        videoSize = VideoSize.UNKNOWN
        surfaceSize = Size.UNKNOWN
        newlyRenderedFirstFrame = false
        invalidateState()
    }

    private fun selectedEngineTrackId(parameters: TrackSelectionParameters, trackType: Int): Int? {
        if (parameters.disabledTrackTypes.contains(trackType)) return -1
        val override: TrackSelectionOverride = parameters.overrides.values.firstOrNull {
            it.getType() == trackType
        } ?: return null
        val index = override.trackIndices.firstOrNull() ?: return -1
        val formatId = override.mediaTrackGroup.getFormat(index).id
        return trackSnapshot.engineTrackId(formatId) ?: -1
    }

    private fun applyTrackSelectionParameters() {
        // MPV rejects stream selectors before a media item has been installed. Keep the Media3
        // parameters now and
        // apply them again from the first TRACKS_CHANGED event.
        if (mediaItem == null || trackSnapshot.tracks.isEmpty()) return
        selectedEngineTrackId(trackSelectionParameters, C.TRACK_TYPE_AUDIO)?.let(engine::selectAudioTrack)
        selectedEngineTrackId(trackSelectionParameters, C.TRACK_TYPE_VIDEO)?.let(engine::selectVideoTrack)
        selectedEngineTrackId(trackSelectionParameters, C.TRACK_TYPE_TEXT)?.let(engine::selectSubtitleTrack)
    }

    private fun requestAudioFocusIfNeeded(): AudioFocusResult {
        if (!handleAudioFocus || audioManager == null) return AudioFocusResult.GRANTED
        if (audioFocusHeld) return AudioFocusResult.GRANTED

        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttributes.getPlatformAudioAttributes())
                .setOnAudioFocusChangeListener(audioFocusListener, applicationHandler)
                .setWillPauseWhenDucked(false)
                .setAcceptsDelayedFocusGain(true)
                .build()
                .also { audioFocusRequest = it }
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusListener,
                audioAttributes.getStreamType(),
                AudioManager.AUDIOFOCUS_GAIN,
            )
        }

        return when (result) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
                audioFocusHeld = true
                AudioFocusResult.GRANTED
            }
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> AudioFocusResult.DELAYED
            else -> AudioFocusResult.FAILED
        }
    }

    private fun abandonAudioFocus() {
        val manager = audioManager
        if (manager != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { request ->
                    runCatching { manager.abandonAudioFocusRequest(request) }
                }
            } else if (audioFocusHeld) {
                @Suppress("DEPRECATION")
                runCatching { manager.abandonAudioFocus(audioFocusListener) }
            }
        }
        audioFocusHeld = false
        audioFocusRequest = null
        if (audioDucked) {
            audioDucked = false
            engine.setVolume(volume)
        }
    }

    private fun handleAudioFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                audioFocusHeld = true
                if (audioDucked) {
                    audioDucked = false
                    engine.setVolume(volume)
                }
                playbackSuppressionReason = Player.PLAYBACK_SUPPRESSION_REASON_NONE
                if (playWhenReady && mediaItem != null) engine.play()
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                audioFocusHeld = false
                audioDucked = false
                playWhenReady = false
                playWhenReadyReason = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS
                playbackSuppressionReason = Player.PLAYBACK_SUPPRESSION_REASON_NONE
                engine.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                audioFocusHeld = false
                playbackSuppressionReason =
                    Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS
                engine.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (!audioDucked) {
                    audioDucked = true
                    engine.setVolume((volume * DUCK_VOLUME_MULTIPLIER).coerceIn(0f, 1f))
                }
            }
        }
        invalidateState()
    }

    private fun durationMsToUs(durationMs: Long): Long = when {
        durationMs == C.TIME_UNSET -> C.TIME_UNSET
        durationMs <= 0L -> durationMs * 1_000L
        durationMs > Long.MAX_VALUE / 1_000L -> Long.MAX_VALUE
        else -> durationMs * 1_000L
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

    companion object {
        private const val DUCK_VOLUME_MULTIPLIER = 0.2f

        private val AVAILABLE_COMMANDS = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_PREPARE,
                Player.COMMAND_STOP,
                Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SEEK_BACK,
                Player.COMMAND_SEEK_FORWARD,
                Player.COMMAND_SET_SPEED_AND_PITCH,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_GET_AUDIO_ATTRIBUTES,
                Player.COMMAND_GET_VOLUME,
                Player.COMMAND_SET_VOLUME,
                Player.COMMAND_SET_AUDIO_ATTRIBUTES,
                Player.COMMAND_SET_VIDEO_SURFACE,
                Player.COMMAND_GET_TEXT,
                Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS,
                Player.COMMAND_GET_TRACKS,
                Player.COMMAND_SET_MEDIA_ITEM,
                Player.COMMAND_CHANGE_MEDIA_ITEMS,
                Player.COMMAND_RELEASE,
            )
            .build()
    }
}
