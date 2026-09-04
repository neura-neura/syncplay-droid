package dev.neura.syncplay.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackServiceSecurityTest {
    @Test
    fun playbackCommandsAreExplicitlyNamespacedAndUnique() {
        val commands = listOf(
            PlaybackService.ACTION_PLAY,
            PlaybackService.ACTION_PAUSE,
            PlaybackService.ACTION_TOGGLE,
            PlaybackService.ACTION_STOP,
            PlaybackService.ACTION_SEEK_TO,
        )

        assertEquals(commands.size, commands.toSet().size)
        assertTrue(commands.all { it.startsWith("dev.neura.syncplay.action.") })
    }

    @Test
    fun seekPositionExtraUsesTheSameExplicitNamespace() {
        assertEquals("dev.neura.syncplay.extra.POSITION_MS", PlaybackService.EXTRA_POSITION_MS)
    }
}
