package dev.neura.syncplay.player

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dev.neura.syncplay.protocol.MediaDescriptor
import dev.neura.syncplay.player.vlc.LibMpvEngine
import dev.neura.syncplay.player.vlc.VlcPlayer
import dev.neura.syncplay.smb.SmbPlaybackEnvironment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Media3 playback owner for Syncplay Droid.
 *
 * The service keeps ExoPlayer alive independently from the Compose activity,
 * exposes a MediaSession for transport controls/notification/Android media
 * buttons, and accepts a small set of explicit intents for the protocol layer.
 * A `content://` URI is the preferred input; callers should grant read access
 * (and persist it when possible) before starting the service.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main.immediate + serviceJob)

    private var exoPlayer: ExoPlayer? = null
    private var mpvPlayer: VlcPlayer? = null
    private var currentPlayer: Player? = null
    private var mediaSession: MediaSession? = null
    private var diagnosticsListener: PlaybackDiagnosticsCollector? = null
    private var openJob: Job? = null
    private val retainedTransientUris = linkedSetOf<Uri>()

    /** Player instance used by a MediaController/synchronizer once created. */
    val playbackPlayer: Player?
        get() = currentPlayer

    /** Scope tied to the service lifecycle for metadata/sync work. */
    val playbackScope: CoroutineScope
        get() = serviceScope

    /** Most recently resolved local file descriptor, if one has been opened. */
    @Volatile
    var currentMediaInfo: ResolvedMediaInfo? = null
        private set

    override fun onCreate() {
        super.onCreate()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        val renderersFactory = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
        val mediaSourceFactory = DefaultMediaSourceFactory(
            SmbPlaybackEnvironment.dataSourceFactory(this),
        )
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMsForStreaming(
                STREAMING_MIN_BUFFER_MS,
                STREAMING_MAX_BUFFER_MS,
                BUFFER_FOR_PLAYBACK_MS,
                BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
            )
            .setBufferDurationsMsForLocalPlayback(
                LOCAL_MIN_BUFFER_MS,
                LOCAL_MAX_BUFFER_MS,
                BUFFER_FOR_PLAYBACK_MS,
                BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
            )
            // Media3 classifies content:// providers as local playback. Prioritize buffered time
            // for SMB-backed MKVs while retaining a byte safety bound for arbitrary HTTP streams.
            .setPrioritizeTimeOverSizeThresholdsForLocalPlayback(true)
            .setPrioritizeTimeOverSizeThresholdsForStreaming(true)
            .setBackBuffer(BACK_BUFFER_DURATION_MS, /* retainBackBufferFromKeyframe = */ true)
            .build()

        val player = ExoPlayer.Builder(this)
            .setRenderersFactory(renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            // Interactive seeks land quickly on a decodable keyframe. Frame-accurate protocol
            // corrections opt into EXACT only for the accepted seek, then restore this mode.
            .setSeekParameters(SeekParameters.CLOSEST_SYNC)
            // SMB-backed content providers can still require the network while
            // ExoPlayer reads a content URI. Keep CPU/Wi-Fi awake only while
            // playback is active.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .apply {
                // Let ExoPlayer own AudioFocus and automatically pause when a
                // competing app requests focus.  The noisy-route receiver is
                // also built into ExoPlayer and pauses on headphone removal.
                setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
                setHandleAudioBecomingNoisy(true)
                addListener(servicePlayerListener)
            }

        // Keep diagnostics on the service-owned player so decoder and renderer callbacks reflect
        // the actual device pipeline (the Activity only owns a MediaController facade).
        diagnosticsListener = PlaybackDiagnosticsCollector { player.currentMediaItem }
        player.addAnalyticsListener(diagnosticsListener!!)
        exoPlayer = player
        currentPlayer = player
        activeProcessPlayer = player
        activeService = this
        PlaybackEngineStore.setActive(PlaybackEngine.MEDIA3, PlaybackEngineReason.DEFAULT)
        mediaSession = MediaSession.Builder(this, player)
            .setId(SESSION_ID)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession?.takeIf {
            // Media3 marks platform/system media controllers as trusted.  The
            // app's own MediaController is allowed explicitly so it remains
            // usable even on devices whose trust service does not recognise
            // the package yet.
            isAllowedController(
                controllerPackage = controllerInfo.packageName,
                applicationPackage = packageName,
                isTrusted = controllerInfo.isTrusted,
            )
        }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Keep the owner available for an active media session after the
        // activity is removed. MediaSessionService will still manage its
        // notification/foreground lifecycle.
        val result = super.onStartCommand(intent, flags, startId)
        if (isAuthorizedPlaybackCommand(intent, PROCESS_COMMAND_TOKEN)) {
            runCatching { handleIntent(intent) }
                .onFailure { error ->
                    // Do not include Intent extras, content URIs or provider
                    // exception messages in logs; those may reveal private
                    // media locations.
                    Log.w(TAG, "Ignoring malformed playback command (${error.javaClass.simpleName})")
                }
        }
        return result
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Do not stop or release here: Android media apps are expected to keep
        // playing after the launcher task is dismissed. The session callback
        // and notification remain the user's way to control playback.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        openJob?.cancel()
        openJob = null
        exoPlayer?.removeListener(servicePlayerListener)
        mpvPlayer?.removeListener(servicePlayerListener)
        // Release the session before the player it references.  Neither object
        // is reused after this point, preventing callbacks into a dead service.
        mediaSession?.release()
        mediaSession = null
        if (activeProcessPlayer === currentPlayer) activeProcessPlayer = null
        if (activeService === this) activeService = null
        diagnosticsListener?.let { listener -> exoPlayer?.removeAnalyticsListener(listener) }
        diagnosticsListener = null
        exoPlayer?.release()
        exoPlayer = null
        mpvPlayer?.release()
        mpvPlayer = null
        currentPlayer = null
        currentMediaInfo = null
        PlaybackDiagnosticsStore.clear()
        retainedTransientUris.clear()
        serviceScope.cancel()
        serviceJob.cancel()
        super.onDestroy()
    }

    private fun handleIntent(intent: Intent?) {
        val player = currentPlayer ?: return
        when (intent?.action) {
            ACTION_OPEN -> {
                val uri = intent.readUriExtra(EXTRA_URI)
                    ?: intent.getStringExtra(EXTRA_URI_STRING)?.let(Uri::parse)
                    ?: return
                openUri(
                    uri = uri,
                    positionMs = intent.getLongExtra(EXTRA_POSITION_MS, 0L),
                    playWhenReady = intent.getBooleanExtra(EXTRA_PLAY_WHEN_READY, false),
                )
            }

            ACTION_RETAIN_URI -> {
                intent.readUriExtra(EXTRA_URI)?.let(retainedTransientUris::add)
            }

            ACTION_PLAY -> player.play()
            ACTION_PAUSE -> player.pause()
            ACTION_TOGGLE -> if (player.isPlaying) player.pause() else player.play()
            ACTION_STOP -> {
                openJob?.cancel()
                openJob = null
                currentMediaInfo = null
                PlaybackDiagnosticsStore.clear()
                player.stop()
                player.clearMediaItems()
            }

            ACTION_SEEK_TO -> {
                val positionMs = intent.getLongExtra(EXTRA_POSITION_MS, Long.MIN_VALUE)
                if (positionMs != Long.MIN_VALUE) {
                    player.useClosestSyncSeek()
                    player.seekTo(positionMs.coerceAtLeast(0L))
                }
            }

            ACTION_SEEK_BY -> {
                val deltaMs = intent.getLongExtra(EXTRA_DELTA_MS, 0L)
                val current = player.currentPosition.coerceAtLeast(0L)
                val target = when {
                    deltaMs > 0L && current > Long.MAX_VALUE - deltaMs -> Long.MAX_VALUE
                    deltaMs < 0L && current < -deltaMs -> 0L
                    else -> (current + deltaMs).coerceAtLeast(0L)
                }
                player.useClosestSyncSeek()
                player.seekTo(target)
            }

            ACTION_SET_RATE -> {
                val rate = intent.getFloatExtra(EXTRA_RATE, 1f)
                    .takeIf { it.isFinite() }
                    ?.coerceIn(MIN_RATE, MAX_RATE)
                    ?: 1f
                // PlaybackParameters is available across all Media3 1.x
                // releases and preserves pitch by default.
                player.setPlaybackParameters(PlaybackParameters(rate))
            }
        }
    }

    private fun openUri(uri: Uri, positionMs: Long, playWhenReady: Boolean) {
        openJob?.cancel()
        PlaybackDiagnosticsStore.restart(uri)
        openJob = serviceScope.launch {
            try {
                val info = withContext(Dispatchers.IO) {
                    MediaInfoResolver.resolve(applicationContext, uri)
                }
                // A newer OPEN may have cancelled this coroutine while metadata
                // was being read; do not install stale media in that case.
                if (!isActive) return@launch

                currentMediaInfo = info
                val preference = PlaybackEngineStore.state.value.preference
                val (engine, reason) = selectPlaybackEngine(
                    preference = preference,
                    displayName = info.displayName,
                    mimeType = info.mimeType,
                    uriPath = info.uri.toString(),
                    sourceAccess = info.sourceAccess,
                )
                if (!switchPlaybackEngine(engine, reason, preserveCurrentMedia = false)) {
                    throw IllegalStateException("Unable to select playback engine")
                }
                val player = currentPlayer ?: throw IllegalStateException("Playback player unavailable")
                val mediaItem = MediaItem.Builder()
                    .setMediaId(uri.toString())
                    .setUri(uri)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(info.displayName)
                            .build(),
                    )
                    .build()
                player.setMediaItem(mediaItem, positionMs.coerceAtLeast(0L))
                player.prepare()
                player.playWhenReady = playWhenReady
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // URI/provider failures must not escape the service's Main
                // coroutine and take down MediaSession playback for all callers.
                currentMediaInfo = null
                Log.w(TAG, "Unable to open media (${error.javaClass.simpleName})")
            }
        }
    }

    private val servicePlayerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // Media metadata is resolved when OPEN is handled.  A controller
            // may advance/clear the item directly, so clear stale descriptors
            // when there is no current media item.
            val activeItem = currentPlayer?.currentMediaItem
            if (mediaItem == null && activeItem == null) {
                currentMediaInfo = null
                PlaybackDiagnosticsStore.clear()
            } else if (mediaItem != null && activeItem == mediaItem) {
                PlaybackDiagnosticsStore.beginIfChanged(mediaItem.localConfiguration?.uri)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (PlaybackEngineStore.state.value.active != PlaybackEngine.MPV) return
            PlaybackDiagnosticsStore.update { current ->
                current.copy(
                    playerErrorCount = current.playerErrorCount + 1,
                    lastPlayerError = "MPV: ${error.errorCodeName}",
                )
            }
        }
    }

    private fun switchPlaybackEngine(
        requested: PlaybackEngine,
        reason: PlaybackEngineReason,
        preserveCurrentMedia: Boolean,
    ): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Playback engine changes must run on the main looper"
        }
        val source = currentPlayer ?: return false
        if (source.engineType() == requested) {
            PlaybackEngineStore.setActive(requested, reason)
            return true
        }

        val target = when (requested) {
            PlaybackEngine.MEDIA3 -> exoPlayer
            PlaybackEngine.MPV -> mpvPlayer ?: run {
                var createdEngine: LibMpvEngine? = null
                runCatching {
                    compatibilityInitializationStage = "libmpv engine constructor"
                    val engine = LibMpvEngine(this) { stage ->
                        compatibilityInitializationStage = "libmpv $stage"
                    }
                    createdEngine = engine
                    compatibilityInitializationStage = "Media3 MPV wrapper constructor"
                    VlcPlayer(this, engine).apply {
                        compatibilityInitializationStage = "audio attributes"
                        setAudioAttributes(playbackAudioAttributes(), /* handleAudioFocus = */ true)
                        compatibilityInitializationStage = "listener"
                        addListener(servicePlayerListener)
                    }.also {
                        createdEngine = null
                        mpvPlayer = it
                    }
                }.onFailure { error ->
                    runCatching { createdEngine?.close() }
                    Log.w(
                        TAG,
                        "Unable to initialize compatibility engine during " +
                            "$compatibilityInitializationStage (${error.javaClass.simpleName})",
                    )
                }.getOrNull()
            }
        } ?: return false

        val item = source.currentMediaItem.takeIf { preserveCurrentMedia }
        val positionMs = source.currentPosition.coerceAtLeast(0L)
        val shouldPlay = source.playWhenReady
        val parameters = source.playbackParameters
        val trackParameters = runCatching { source.trackSelectionParameters }.getOrNull()

        var sessionCommitted = false
        return runCatching {
            target.stop()
            target.clearMediaItems()
            if (item != null) {
                trackParameters?.takeIf {
                    target.isCommandAvailable(Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS)
                }?.let { target.trackSelectionParameters = it }
                target.playbackParameters = parameters
                target.setMediaItem(item, positionMs)
                target.playerError?.let { throw it }
                target.prepare()
                target.playWhenReady = shouldPlay
            }

            mediaSession?.setPlayer(target) ?: error("MediaSession unavailable")
            sessionCommitted = true
            currentPlayer = target
            activeProcessPlayer = target
            PlaybackEngineStore.setActive(requested, reason)

            // Ignore the old player's transition-to-empty callback: the target is now the source
            // of truth and already owns the preserved item (when requested).
            runCatching { source.removeListener(servicePlayerListener) }
            runCatching { source.stop() }
            runCatching { source.clearMediaItems() }
            runCatching { source.addListener(servicePlayerListener) }
            true
        }.onFailure { error ->
            if (!sessionCommitted) {
                // Keep the old session player/media intact and release any descriptor opened by
                // the rejected target before reporting the failed switch.
                runCatching { target.stop() }
                runCatching { target.clearMediaItems() }
            }
            Log.w(TAG, "Unable to change playback engine (${error.javaClass.simpleName})")
        }.getOrDefault(false)
    }

    private fun Player.engineType(): PlaybackEngine =
        if (this is VlcPlayer) PlaybackEngine.MPV else PlaybackEngine.MEDIA3

    private fun Player.useClosestSyncSeek() {
        if (this is ExoPlayer) setSeekParameters(SeekParameters.CLOSEST_SYNC)
    }

    private fun playbackAudioAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
        .build()

    private var compatibilityInitializationStage: String = "not started"

    companion object {
        const val ACTION_OPEN = "dev.neura.syncplay.action.OPEN"
        const val ACTION_PLAY = "dev.neura.syncplay.action.PLAY"
        const val ACTION_PAUSE = "dev.neura.syncplay.action.PAUSE"
        const val ACTION_TOGGLE = "dev.neura.syncplay.action.TOGGLE"
        const val ACTION_STOP = "dev.neura.syncplay.action.STOP"
        const val ACTION_SEEK_TO = "dev.neura.syncplay.action.SEEK_TO"
        const val ACTION_SEEK_BY = "dev.neura.syncplay.action.SEEK_BY"
        const val ACTION_SET_RATE = "dev.neura.syncplay.action.SET_RATE"
        const val ACTION_RETAIN_URI = "dev.neura.syncplay.action.RETAIN_URI"

        const val EXTRA_URI = "dev.neura.syncplay.extra.URI"
        const val EXTRA_URI_STRING = "dev.neura.syncplay.extra.URI_STRING"
        const val EXTRA_POSITION_MS = "dev.neura.syncplay.extra.POSITION_MS"
        const val EXTRA_DELTA_MS = "dev.neura.syncplay.extra.DELTA_MS"
        const val EXTRA_PLAY_WHEN_READY = "dev.neura.syncplay.extra.PLAY_WHEN_READY"
        const val EXTRA_RATE = "dev.neura.syncplay.extra.RATE"

        const val SESSION_ID = "syncplay-droid"
        /** Internal marker added by the same-process helpers below. */
        internal const val EXTRA_COMMAND_TOKEN = "dev.neura.syncplay.extra.COMMAND_TOKEN"
        private const val MIN_RATE = 0.25f
        private const val MAX_RATE = 4f
        private const val LOCAL_MIN_BUFFER_MS = 20_000
        private const val LOCAL_MAX_BUFFER_MS = 120_000
        private const val STREAMING_MIN_BUFFER_MS = 30_000
        private const val STREAMING_MAX_BUFFER_MS = 120_000
        private const val BUFFER_FOR_PLAYBACK_MS = 5_000
        private const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 15_000
        private const val BACK_BUFFER_DURATION_MS = 30_000
        private const val TAG = "PlaybackService"

        private val PROCESS_COMMAND_TOKEN: String = UUID.randomUUID().toString()
        @Volatile
        private var activeProcessPlayer: Player? = null
        @Volatile
        private var activeService: PlaybackService? = null
        private val INTERNAL_COMMAND_ACTIONS = setOf(
            ACTION_OPEN,
            ACTION_PLAY,
            ACTION_PAUSE,
            ACTION_TOGGLE,
            ACTION_STOP,
            ACTION_SEEK_TO,
            ACTION_SEEK_BY,
            ACTION_SET_RATE,
            ACTION_RETAIN_URI,
        )

        /**
         * A process-local capability for the explicit helper intents.  The
         * service remains exported for MediaSession discovery, but another app
         * cannot manufacture these commands without the random token held by
         * this process.
         */
        internal fun isAuthorizedPlaybackCommand(intent: Intent?, expectedToken: String): Boolean {
            return runCatching {
                isAuthorizedPlaybackAction(
                    action = intent?.action,
                    token = intent?.getStringExtra(EXTRA_COMMAND_TOKEN),
                    expectedToken = expectedToken,
                )
            }.getOrDefault(false)
        }

        /** Pure policy predicate kept separate so authorization stays unit-testable. */
        internal fun isAuthorizedPlaybackAction(
            action: String?,
            token: String?,
            expectedToken: String,
        ): Boolean = action in INTERNAL_COMMAND_ACTIONS && token == expectedToken

        /** Controller trust policy used by [onGetSession] and unit tests. */
        internal fun isAllowedController(
            controllerPackage: String?,
            applicationPackage: String,
            isTrusted: Boolean,
        ): Boolean = isTrusted || controllerPackage == applicationPackage

        /**
         * Apply one frame-accurate protocol correction synchronously to the process-local player.
         *
         * This intentionally is not an Intent command: a queued command could arrive after the
         * user has replaced the media item. The identity check and application-looper requirement
         * make the acknowledgement correspond to the exact player/item that accepted the seek.
         */
        internal fun seekToSynchronizedNow(
            expectedMediaIdentity: String?,
            positionMs: Long,
        ): Boolean {
            val player = activeProcessPlayer ?: return false
            if (Looper.myLooper() != player.applicationLooper) return false
            val item = player.currentMediaItem ?: return false
            val actualIdentity = item.mediaId.takeIf { it.isNotBlank() }
                ?: item.localConfiguration?.uri?.toString()
            if (expectedMediaIdentity.isNullOrBlank() || actualIdentity != expectedMediaIdentity) {
                return false
            }
            if (!player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) return false
            return runCatching {
                if (player is ExoPlayer) {
                    player.setSeekParameters(SeekParameters.EXACT)
                    try {
                        player.seekTo(positionMs.coerceAtLeast(0L))
                    } finally {
                        player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
                    }
                } else {
                    player.seekTo(positionMs.coerceAtLeast(0L))
                }
                true
            }.getOrDefault(false)
        }

        /** Replace the session backend synchronously without disconnecting its MediaController. */
        internal fun setPlaybackEngineNow(
            engine: PlaybackEngine,
            reason: PlaybackEngineReason,
            preserveCurrentMedia: Boolean = true,
        ): Boolean {
            val service = activeService ?: return false
            if (Looper.myLooper() != Looper.getMainLooper()) return false
            return runCatching {
                service.switchPlaybackEngine(engine, reason, preserveCurrentMedia)
            }.getOrDefault(false)
        }

        /** Start/reuse the service and open a SAF/file URI. */
        fun open(
            context: Context,
            uri: Uri,
            playWhenReady: Boolean = false,
            positionMs: Long = 0L,
        ) {
            val intent = Intent(context, PlaybackService::class.java)
                .setAction(ACTION_OPEN)
                .putExtra(EXTRA_URI, uri)
                .putExtra(EXTRA_POSITION_MS, positionMs.coerceAtLeast(0L))
                .putExtra(EXTRA_PLAY_WHEN_READY, playWhenReady)
            start(context, intent)
        }

        /** Start/reuse the service with one of the public transport actions. */
        fun dispatch(context: Context, action: String): Unit = start(
            context,
            Intent(context, PlaybackService::class.java).setAction(action),
        )

        fun seekTo(context: Context, positionMs: Long) = start(
            context,
            Intent(context, PlaybackService::class.java)
                .setAction(ACTION_SEEK_TO)
                .putExtra(EXTRA_POSITION_MS, positionMs.coerceAtLeast(0L)),
        )

        fun seekBy(context: Context, deltaMs: Long) = start(
            context,
            Intent(context, PlaybackService::class.java)
                .setAction(ACTION_SEEK_BY)
                .putExtra(EXTRA_DELTA_MS, deltaMs),
        )

        fun setRate(context: Context, rate: Float) = start(
            context,
            Intent(context, PlaybackService::class.java)
                .setAction(ACTION_SET_RATE)
                .putExtra(EXTRA_RATE, rate),
        )

        /**
         * Keep a transient GET_CONTENT URI grant for the lifetime of this started service.
         * Android associates an Intent URI grant with a started service until that service stops,
         * so playback can continue after the picker Activity/task is removed.
         */
        fun retainReadGrant(context: Context, uri: Uri) {
            if (!uri.scheme.equals("content", ignoreCase = true)) return
            val intent = Intent(context, PlaybackService::class.java)
                .setAction(ACTION_RETAIN_URI)
                .putExtra(EXTRA_URI, uri)
            intent.clipData = ClipData.newRawUri("media", uri)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            start(context, intent)
        }

        private fun start(context: Context, intent: Intent) {
            intent.putExtra(EXTRA_COMMAND_TOKEN, PROCESS_COMMAND_TOKEN)
            // MediaSessionService promotes itself when active playback starts.
            // For an OPEN that is intentionally paused, starting as a
            // foreground service would require a notification within Android's
            // five-second deadline even though no playback is active.  Start
            // those requests as a regular service while the Activity is in the
            // foreground; PLAY/TOGGLE and autoplay requests use the foreground
            // path so background playback is allowed on API 26+.
            val startsPlayback = intent.action == ACTION_PLAY || intent.action == ACTION_TOGGLE ||
                (intent.action == ACTION_OPEN && intent.getBooleanExtra(EXTRA_PLAY_WHEN_READY, false))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && startsPlayback) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }

        @Suppress("DEPRECATION")
        private fun Intent.readUriExtra(key: String): Uri? = if (Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(key, Uri::class.java)
        } else {
            getParcelableExtra(key)
        }
    }

}
