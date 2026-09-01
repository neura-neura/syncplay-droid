package dev.neura.syncplay.player

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dev.neura.syncplay.protocol.MediaDescriptor
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
    private var mediaSession: MediaSession? = null
    private var openJob: Job? = null
    private val retainedTransientUris = linkedSetOf<Uri>()

    /** Player instance used by a MediaController/synchronizer once created. */
    val playbackPlayer: ExoPlayer?
        get() = exoPlayer

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

        val player = ExoPlayer.Builder(this)
            // EXACT avoids ExoPlayer selecting a nearby keyframe when Syncplay
            // applies a remote seek.  It is still safe for codecs without
            // frame-accurate seek; ExoPlayer falls back internally as needed.
            .setSeekParameters(SeekParameters.EXACT)
            .build()
            .apply {
                // Let ExoPlayer own AudioFocus and automatically pause when a
                // competing app requests focus.  The noisy-route receiver is
                // also built into ExoPlayer and pauses on headphone removal.
                setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
                setHandleAudioBecomingNoisy(true)
                addListener(servicePlayerListener)
            }

        exoPlayer = player
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
        // Release the session before the player it references.  Neither object
        // is reused after this point, preventing callbacks into a dead service.
        mediaSession?.release()
        mediaSession = null
        exoPlayer?.release()
        exoPlayer = null
        currentMediaInfo = null
        retainedTransientUris.clear()
        serviceScope.cancel()
        serviceJob.cancel()
        super.onDestroy()
    }

    private fun handleIntent(intent: Intent?) {
        val player = exoPlayer ?: return
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
                player.stop()
                player.clearMediaItems()
            }

            ACTION_SEEK_TO -> {
                val positionMs = intent.getLongExtra(EXTRA_POSITION_MS, Long.MIN_VALUE)
                if (positionMs != Long.MIN_VALUE) player.seekTo(positionMs.coerceAtLeast(0L))
            }

            ACTION_SEEK_BY -> {
                val deltaMs = intent.getLongExtra(EXTRA_DELTA_MS, 0L)
                val current = player.currentPosition.coerceAtLeast(0L)
                val target = when {
                    deltaMs > 0L && current > Long.MAX_VALUE - deltaMs -> Long.MAX_VALUE
                    deltaMs < 0L && current < -deltaMs -> 0L
                    else -> (current + deltaMs).coerceAtLeast(0L)
                }
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
        val player = exoPlayer ?: return
        openJob?.cancel()
        openJob = serviceScope.launch {
            try {
                val info = withContext(Dispatchers.IO) {
                    MediaInfoResolver.resolve(applicationContext, uri)
                }
                // A newer OPEN may have cancelled this coroutine while metadata
                // was being read; do not install stale media in that case.
                if (!isActive) return@launch

                currentMediaInfo = info
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
            if (mediaItem == null) currentMediaInfo = null
        }
    }

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
        private const val TAG = "PlaybackService"

        private val PROCESS_COMMAND_TOKEN: String = UUID.randomUUID().toString()
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
