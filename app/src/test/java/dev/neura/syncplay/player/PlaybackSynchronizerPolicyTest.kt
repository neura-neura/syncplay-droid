package dev.neura.syncplay.player

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
    fun idleEndedAndFatalErrorAreNeverAdvertisedAsPlaying() {
        assertTrue(isEffectivelyPaused(true, androidx.media3.common.Player.STATE_IDLE, false))
        assertTrue(isEffectivelyPaused(true, androidx.media3.common.Player.STATE_ENDED, false))
        assertTrue(isEffectivelyPaused(true, androidx.media3.common.Player.STATE_READY, true))
        assertEquals(false, isEffectivelyPaused(true, androidx.media3.common.Player.STATE_BUFFERING, false))
    }

    @Test
    fun playWhenReadyChangeWinsOverCoalescedTimelineForSameMedia() {
        val reason = classifyLocalPlaybackChange(
            doSeek = false,
            mediaIdentityChanged = false,
            mediaItemTransition = true,
            timelineChanged = true,
            playbackParametersChanged = false,
            playWhenReadyChanged = true,
        )

        assertEquals(LocalPlaybackChangeReason.PLAYBACK, reason)
    }

    @Test
    fun mediaIdentityChangeWinsOverCoalescedPlayWhenReadyChange() {
        val reason = classifyLocalPlaybackChange(
            doSeek = false,
            mediaIdentityChanged = true,
            mediaItemTransition = true,
            timelineChanged = true,
            playbackParametersChanged = false,
            playWhenReadyChanged = true,
        )

        assertEquals(LocalPlaybackChangeReason.MEDIA_ITEM, reason)
    }

    @Test
    fun timelineWithoutPlayWhenReadyChangeRemainsMediaItemEvent() {
        val reason = classifyLocalPlaybackChange(
            doSeek = false,
            mediaIdentityChanged = false,
            mediaItemTransition = false,
            timelineChanged = true,
            playbackParametersChanged = false,
            playWhenReadyChanged = false,
        )

        assertEquals(LocalPlaybackChangeReason.MEDIA_ITEM, reason)
    }
}
