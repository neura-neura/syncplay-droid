package dev.neura.syncplay.player

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import dev.neura.syncplay.protocol.RemotePlaybackState
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSynchronizerSeekTest {
    private val config = SynchronizerConfig(
        hardSeekThresholdMs = 1_500L,
        driftToleranceMs = 100L,
        nowElapsedRealtimeMs = { 0L },
    )

    @Test
    fun exactSeekBridgeIsUsedOnlyForAnExplicitRemoteSeek() {
        val recordingPlayer = RecordingPlayer()
        val synchronizer = synchronizer(recordingPlayer.player)
        var exactSeekCalls = 0
        synchronizer.onRemoteSeekRequested = {
            exactSeekCalls++
            true
        }

        val automatic = synchronizer.applyRemoteState(
            RemotePlaybackState(
                positionSeconds = 10.0,
                paused = false,
                doSeek = false,
                setBy = null,
                messageAgeSeconds = 0.0,
            ),
        )
        assertTrue(automatic.seekApplied)
        assertEquals(0, exactSeekCalls)
        assertEquals(listOf(10_000L), recordingPlayer.seekTargets)

        val explicit = synchronizer.applyRemoteState(
            RemotePlaybackState(
                positionSeconds = 20.0,
                paused = false,
                doSeek = true,
                setBy = "remote-user",
                messageAgeSeconds = 0.0,
            ),
        )
        assertTrue(explicit.seekApplied)
        assertEquals(1, exactSeekCalls)
        // The exact bridge owns the explicit seek; the regular Player path is not also invoked.
        assertEquals(listOf(10_000L), recordingPlayer.seekTargets)

        synchronizer.close()
    }

    @Test
    fun automaticCorrectionWaitsForReadyButExplicitSeekMayRunWhileBuffering() {
        val recordingPlayer = RecordingPlayer().apply {
            playbackState = Player.STATE_BUFFERING
        }
        val synchronizer = synchronizer(recordingPlayer.player)
        var exactSeekCalls = 0
        synchronizer.onRemoteSeekRequested = {
            exactSeekCalls++
            true
        }

        val automatic = synchronizer.applyRemoteState(
            RemotePlaybackState(
                positionSeconds = 10.0,
                paused = false,
                doSeek = false,
                setBy = null,
                messageAgeSeconds = 0.0,
            ),
        )
        assertFalse(automatic.seekApplied)
        assertEquals(0, exactSeekCalls)
        assertTrue(recordingPlayer.seekTargets.isEmpty())

        val explicit = synchronizer.applyRemoteState(
            RemotePlaybackState(
                positionSeconds = 10.0,
                paused = false,
                doSeek = true,
                setBy = "remote-user",
                messageAgeSeconds = 0.0,
            ),
        )
        assertTrue(explicit.seekApplied)
        assertEquals(1, exactSeekCalls)
        assertTrue(recordingPlayer.seekTargets.isEmpty())

        synchronizer.close()
    }

    private fun synchronizer(player: Player): PlaybackSynchronizer = PlaybackSynchronizer(
        player = player,
        scope = CoroutineScope(StandardTestDispatcher() + Job()),
        config = config,
    )

    /** Minimal Player double backed by a Java proxy so this test stays on the JVM. */
    private class RecordingPlayer {
        var currentPosition = 0L
        var playbackState = Player.STATE_READY
        var playWhenReady = true
        val seekTargets = mutableListOf<Long>()
        private val mediaItem = MediaItem.Builder()
            .setMediaId("recording-item")
            .build()
        private var playbackParameters = PlaybackParameters(1f)

        val player: Player = Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java),
            InvocationHandler { proxy, method, args -> invoke(proxy, method, args) },
        ) as Player

        private fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? = when (method.name) {
            "toString" -> "RecordingPlayer"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            "addListener", "removeListener" -> null
            "isCommandAvailable" -> true
            "getCurrentMediaItem" -> mediaItem
            "getMediaItemCount" -> 1
            "getCurrentMediaItemIndex" -> 0
            "getCurrentPosition" -> currentPosition
            "getDuration" -> 0L
            "getBufferedPosition" -> currentPosition
            "getPlaybackState" -> playbackState
            "getPlayWhenReady" -> playWhenReady
            "setPlayWhenReady" -> {
                playWhenReady = args?.firstOrNull() as? Boolean ?: playWhenReady
                null
            }
            "isPlaying" -> playWhenReady && playbackState == Player.STATE_READY
            "getPlayerError" -> null
            "getPlaybackParameters" -> playbackParameters
            "setPlaybackParameters" -> {
                playbackParameters = args?.firstOrNull() as? PlaybackParameters ?: playbackParameters
                null
            }
            "setPlaybackSpeed" -> {
                playbackParameters = PlaybackParameters(args?.firstOrNull() as? Float ?: 1f)
                null
            }
            "play" -> {
                playWhenReady = true
                null
            }
            "pause" -> {
                playWhenReady = false
                null
            }
            "seekTo" -> {
                val target = args?.firstOrNull() as? Long ?: 0L
                seekTargets += target
                currentPosition = target
                null
            }
            else -> defaultValue(method.returnType)
        }

        private fun defaultValue(type: Class<*>): Any? = when (type) {
            Boolean::class.javaPrimitiveType -> false
            Byte::class.javaPrimitiveType -> 0.toByte()
            Short::class.javaPrimitiveType -> 0.toShort()
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            Float::class.javaPrimitiveType -> 0f
            Double::class.javaPrimitiveType -> 0.0
            Char::class.javaPrimitiveType -> '\u0000'
            else -> null
        }
    }
}
