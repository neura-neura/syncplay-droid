package dev.neura.syncplay.player.vlc

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VlcPlayerStateTest {
    @Test
    fun lifecycleMapsToMedia3States() {
        assertEquals(Player.STATE_IDLE, VlcPlaybackPhase.IDLE.toMedia3PlaybackState())
        assertEquals(Player.STATE_BUFFERING, VlcPlaybackPhase.OPENING.toMedia3PlaybackState())
        assertEquals(Player.STATE_BUFFERING, VlcPlaybackPhase.BUFFERING.toMedia3PlaybackState())
        assertEquals(Player.STATE_READY, VlcPlaybackPhase.PLAYING.toMedia3PlaybackState())
        assertEquals(Player.STATE_READY, VlcPlaybackPhase.PAUSED.toMedia3PlaybackState())
        assertEquals(Player.STATE_ENDED, VlcPlaybackPhase.ENDED.toMedia3PlaybackState())
    }

    @Test
    fun endedAndErrorCannotAdvertisePlayIntent() {
        assertFalse(VlcPlaybackPhase.ENDED.normalizedPlayWhenReady(true))
        assertFalse(VlcPlaybackPhase.ERROR.normalizedPlayWhenReady(true))
        assertTrue(VlcPlaybackPhase.BUFFERING.normalizedPlayWhenReady(true))
        assertTrue(VlcPlaybackPhase.PLAYING.isPlaying(true))
        assertFalse(VlcPlaybackPhase.PAUSED.isPlaying(true))
    }
}
