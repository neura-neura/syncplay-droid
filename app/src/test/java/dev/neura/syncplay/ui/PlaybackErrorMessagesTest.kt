package dev.neura.syncplay.ui

import java.io.FileNotFoundException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackErrorMessagesTest {
    @Test
    fun codecFailureIsConciseAndActionable() {
        val message = friendlyPlaybackError(IllegalStateException("native decoder detail"))

        assertTrue(message.contains("MPV"))
        assertFalse(message.contains("native decoder detail"))
    }

    @Test
    fun missingPermissionAsksForAReselection() {
        assertTrue(
            friendlyPlaybackError(FileNotFoundException("private/path"))
                .contains("seleccionarlo"),
        )
    }
}
