package dev.neura.syncplay.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import dev.neura.syncplay.MainActivity
import dev.neura.syncplay.R
import dev.neura.syncplay.player.mpv.MpvEventOrigin
import dev.neura.syncplay.player.mpv.MpvPlaybackPhase
import dev.neura.syncplay.player.mpv.MpvPlaybackSession
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Process-local owner of the sole MPV session.
 *
 * Android's framework media session is used only for headset/lock-screen controls and never for
 * decoding or track selection. MPV remains the only playback engine.
 */
class PlaybackService : Service() {
    inner class LocalBinder : Binder() {
        val playbackSession: MpvPlaybackSession get() = session
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var session: MpvPlaybackSession
    private lateinit var platformSession: MediaSession
    private var foreground = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        session = MpvPlaybackSession(this)
        activeSession.set(session)
        platformSession = MediaSession(this, SESSION_TAG).apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { session.play(MpvEventOrigin.USER) }
                override fun onPause() { session.pause(MpvEventOrigin.USER) }
                override fun onStop() { session.stop(MpvEventOrigin.USER) }
                override fun onSeekTo(pos: Long) {
                    session.seekTo(pos.coerceAtLeast(0L), exact = true, origin = MpvEventOrigin.USER)
                }
            })
            isActive = true
        }
        scope.launch {
            session.snapshot.collect { snapshot ->
                platformSession.setMetadata(
                    MediaMetadata.Builder()
                        .putString(MediaMetadata.METADATA_KEY_TITLE, snapshot.title ?: getString(R.string.app_name))
                        .putLong(MediaMetadata.METADATA_KEY_DURATION, snapshot.durationMs.coerceAtLeast(0L))
                        .build(),
                )
                val playbackState = when (snapshot.phase) {
                    MpvPlaybackPhase.PLAYING -> PlaybackState.STATE_PLAYING
                    MpvPlaybackPhase.PAUSED -> PlaybackState.STATE_PAUSED
                    MpvPlaybackPhase.OPENING,
                    MpvPlaybackPhase.BUFFERING,
                    -> PlaybackState.STATE_BUFFERING
                    MpvPlaybackPhase.ENDED -> PlaybackState.STATE_STOPPED
                    MpvPlaybackPhase.ERROR -> PlaybackState.STATE_ERROR
                    else -> PlaybackState.STATE_NONE
                }
                platformSession.setPlaybackState(
                    PlaybackState.Builder()
                        .setActions(
                            PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                                PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SEEK_TO or
                                PlaybackState.ACTION_STOP,
                        )
                        .setState(playbackState, snapshot.positionMs, snapshot.rate)
                        .build(),
                )
                updateNotification(snapshot.title, snapshot.playWhenReady, snapshot.mediaIdentity != null)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> session.play(MpvEventOrigin.USER)
            ACTION_PAUSE -> session.pause(MpvEventOrigin.USER)
            ACTION_TOGGLE -> if (session.snapshot.value.playWhenReady) {
                session.pause(MpvEventOrigin.USER)
            } else {
                session.play(MpvEventOrigin.USER)
            }
            ACTION_STOP -> session.stop(MpvEventOrigin.USER)
            ACTION_SEEK_TO -> session.seekTo(
                intent.getLongExtra(EXTRA_POSITION_MS, 0L),
                exact = true,
                origin = MpvEventOrigin.USER,
            )
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        activeSession.compareAndSet(session, null)
        scope.cancel()
        if (foreground) {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        platformSession.release()
        session.close()
        super.onDestroy()
    }

    private fun updateNotification(title: String?, playing: Boolean, hasMedia: Boolean) {
        val manager = getSystemService(NotificationManager::class.java)
        if (!hasMedia) {
            if (foreground) {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            foreground = false
            manager.cancel(NOTIFICATION_ID)
            return
        }
        val notification = buildNotification(title, playing)
        if (playing && !foreground) {
            startForeground(NOTIFICATION_ID, notification)
            foreground = true
        } else {
            manager.notify(NOTIFICATION_ID, notification)
            if (!playing && foreground) {
                @Suppress("DEPRECATION")
                stopForeground(false)
                foreground = false
            }
        }
    }

    private fun buildNotification(title: String?, playing: Boolean): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val toggleAction = if (playing) ACTION_PAUSE else ACTION_PLAY
        val toggleLabel = if (playing) "Pausar" else "Reproducir"
        val toggleIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, PlaybackService::class.java).setAction(toggleAction),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, PlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val actionIcon = Icon.createWithResource(this, R.drawable.ic_launcher_foreground)
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title ?: getString(R.string.app_name))
            .setContentText("Reproducción sincronizada con MPV")
            .setContentIntent(openApp)
            .setOnlyAlertOnce(true)
            .setOngoing(playing)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(actionIcon, toggleLabel, toggleIntent).build())
            .addAction(Notification.Action.Builder(actionIcon, "Detener", stopIntent).build())
            .setStyle(Notification.MediaStyle().setMediaSession(platformSession.sessionToken).setShowActionsInCompactView(0))
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Reproducción",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Controles del reproductor MPV" },
        )
    }

    companion object {
        const val ACTION_PLAY = "dev.neura.syncplay.action.PLAY"
        const val ACTION_PAUSE = "dev.neura.syncplay.action.PAUSE"
        const val ACTION_TOGGLE = "dev.neura.syncplay.action.TOGGLE"
        const val ACTION_STOP = "dev.neura.syncplay.action.STOP"
        const val ACTION_SEEK_TO = "dev.neura.syncplay.action.SEEK_TO"
        const val EXTRA_POSITION_MS = "dev.neura.syncplay.extra.POSITION_MS"
        private const val SESSION_TAG = "syncplay-droid-mpv"
        private const val NOTIFICATION_CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 7401
        private val activeSession = AtomicReference<MpvPlaybackSession?>()

        fun currentSession(): MpvPlaybackSession? = activeSession.get()

        fun ensureStarted(context: Context) {
            context.startService(Intent(context, PlaybackService::class.java))
        }

        fun retainReadGrant(context: Context, uri: Uri) {
            if (!uri.scheme.equals("content", ignoreCase = true)) return
            val intent = Intent(context, PlaybackService::class.java)
            intent.clipData = ClipData.newRawUri("media", uri)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startService(intent)
        }
    }
}
