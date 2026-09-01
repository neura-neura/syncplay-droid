package dev.neura.syncplay.ui

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackErrorMessagesTest {
    @Test
    fun codecFailureIsConciseAndActionable() {
        val message = friendlyPlaybackError(PlaybackException.ERROR_CODE_DECODING_FAILED)

        assertTrue(message.contains("decodificar"))
        assertFalse(message.contains("MediaCodec"))
    }

    @Test
    fun missingPermissionAsksForAReselection() {
        assertTrue(
            friendlyPlaybackError(PlaybackException.ERROR_CODE_IO_NO_PERMISSION)
                .contains("seleccionarlo"),
        )
    }
}
