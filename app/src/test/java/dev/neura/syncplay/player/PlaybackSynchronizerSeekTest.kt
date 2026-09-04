package dev.neura.syncplay.player

import dev.neura.syncplay.player.mpv.MpvPlaybackPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackSynchronizerSeekTest {
    @Test
    fun playbackProgressUsesMpvMillisecondsForProtocolSeconds() {
        val progress = PlaybackProgress(
            positionMs = 12_345L,
            durationMs = 60_000L,
            bufferedPositionMs = 30_000L,
            isPlaying = true,
            playWhenReady = true,
            phase = MpvPlaybackPhase.PLAYING,
            mediaIdentity = "movie",
        )

        assertEquals(12.345, progress.positionSeconds, 0.0001)
        assertEquals(60.0, progress.durationSeconds!!, 0.0001)
        assertEquals(MpvPlaybackPhase.PLAYING, progress.phase)
    }

    @Test
    fun unknownMpvDurationRemainsUnknown() {
        val progress = PlaybackProgress(
            positionMs = 0L,
            durationMs = -1L,
            bufferedPositionMs = 0L,
            isPlaying = false,
            playWhenReady = false,
            phase = MpvPlaybackPhase.OPENING,
            mediaIdentity = "movie",
        )

        assertNull(progress.durationSeconds)
    }
}
