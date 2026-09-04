package dev.neura.syncplay.player.mpv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvPlayerStateTest {
    @Test
    fun lifecycleHelpersKeepNativeIntentSemantics() {
        assertFalse(MpvPlaybackPhase.ENDED.normalizedPlayWhenReady(true))
        assertFalse(MpvPlaybackPhase.ERROR.normalizedPlayWhenReady(true))
        assertTrue(MpvPlaybackPhase.BUFFERING.normalizedPlayWhenReady(true))
        assertTrue(MpvPlaybackPhase.PLAYING.isPlaying(true))
        assertFalse(MpvPlaybackPhase.PAUSED.isPlaying(true))
    }

    @Test
    fun originsAreExplicitAndStable() {
        assertTrue(MpvEventOrigin.entries.contains(MpvEventOrigin.REMOTE))
        assertTrue(MpvEventOrigin.entries.contains(MpvEventOrigin.CORRECTION))
    }
}
