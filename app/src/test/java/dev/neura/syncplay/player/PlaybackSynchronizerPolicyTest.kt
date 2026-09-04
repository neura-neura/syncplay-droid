package dev.neura.syncplay.player

import dev.neura.syncplay.player.mpv.MpvPlaybackPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSynchronizerPolicyTest {
    private val config = SynchronizerConfig(
        hardSeekThresholdMs = 1_500L,
        driftToleranceMs = 100L,
        maxSpeedAdjustment = 0.05f,
        speedGainPerSecond = 0.02f,
        nowElapsedRealtimeMs = { 0L },
    )

    @Test
    fun explicitSeekAlwaysWinsOverSmallDrift() {
        val decision = DriftPolicy.decide(
            errorMs = 20L,
            paused = false,
            forceSeek = true,
            baseSpeed = 1f,
            currentSpeed = 1f,
            config = config,
        )

        assertEquals(DriftAction.SEEK, decision.action)
    }

    @Test
    fun largeDriftUsesSeek() {
        val decision = DriftPolicy.decide(
            errorMs = -1_500L,
            paused = false,
            forceSeek = false,
            baseSpeed = 1f,
            currentSpeed = 1f,
            config = config,
        )

        assertEquals(DriftAction.SEEK, decision.action)
    }

    @Test
    fun smallPositiveDriftSpeedsUpWithinCap() {
        val decision = DriftPolicy.decide(
            errorMs = 1_000L,
            paused = false,
            forceSeek = false,
            baseSpeed = 1f,
            currentSpeed = 1f,
            config = config,
        )

        assertEquals(DriftAction.ADJUST_SPEED, decision.action)
        assertEquals(1.02f, decision.speed!!, 0.0001f)
    }

    @Test
    fun toleranceAndPausedStateResetSpeed() {
        val inTolerance = DriftPolicy.decide(
            errorMs = 100L,
            paused = false,
            forceSeek = false,
            baseSpeed = 1f,
            currentSpeed = 1.03f,
            config = config,
        )
        val paused = DriftPolicy.decide(
            errorMs = -50L,
            paused = true,
            forceSeek = false,
            baseSpeed = 1f,
            currentSpeed = 1.03f,
            config = config,
        )

        assertEquals(DriftAction.RESET_SPEED, inTolerance.action)
        assertEquals(DriftAction.RESET_SPEED, paused.action)
        assertTrue(inTolerance.speed == null && paused.speed == null)
    }

    @Test
    fun pausedPeerOutsideToleranceUsesSeek() {
        val decision = DriftPolicy.decide(
            errorMs = -500L,
            paused = true,
            forceSeek = false,
            baseSpeed = 1f,
            currentSpeed = 1f,
            config = config,
        )

        assertEquals(DriftAction.SEEK, decision.action)
    }

    @Test
    fun automaticCorrectionDoesNotRestartAPlayerThatIsBuffering() {
        assertEquals(
            false,
            canSeekForRemoteCorrection(
                phase = MpvPlaybackPhase.BUFFERING,
                forceSeek = false,
            ),
        )
        assertEquals(
            true,
            canSeekForRemoteCorrection(
                phase = MpvPlaybackPhase.PLAYING,
                forceSeek = false,
            ),
        )
    }

    @Test
    fun explicitRemoteSeekIsStillAllowedWhileBuffering() {
        assertEquals(
            true,
            canSeekForRemoteCorrection(
                phase = MpvPlaybackPhase.BUFFERING,
                forceSeek = true,
            ),
        )
    }

    @Test
    fun idleEndedAndFatalErrorAreNeverAdvertisedAsPlaying() {
        assertTrue(isEffectivelyPaused(true, MpvPlaybackPhase.IDLE, false))
        assertTrue(isEffectivelyPaused(true, MpvPlaybackPhase.ENDED, false))
        assertTrue(isEffectivelyPaused(true, MpvPlaybackPhase.ERROR, false))
        assertTrue(isEffectivelyPaused(true, MpvPlaybackPhase.PLAYING, true))
        assertEquals(false, isEffectivelyPaused(true, MpvPlaybackPhase.BUFFERING, false))
    }
}
