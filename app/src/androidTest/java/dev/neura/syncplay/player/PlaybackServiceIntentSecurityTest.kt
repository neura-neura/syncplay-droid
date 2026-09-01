package dev.neura.syncplay.player

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackServiceIntentSecurityTest {
    @Test
    fun intentWrapperRequiresTheTokenAndHandlesWrongExtraTypes() {
        val valid = Intent()
            .setAction(PlaybackService.ACTION_PAUSE)
            .putExtra(PlaybackService.EXTRA_COMMAND_TOKEN, "process-token")
        assertTrue(PlaybackService.isAuthorizedPlaybackCommand(valid, "process-token"))

        val wrongToken = Intent()
            .setAction(PlaybackService.ACTION_PAUSE)
            .putExtra(PlaybackService.EXTRA_COMMAND_TOKEN, "other-token")
        assertFalse(PlaybackService.isAuthorizedPlaybackCommand(wrongToken, "process-token"))

        val wrongType = Intent()
            .setAction(PlaybackService.ACTION_OPEN)
            .putExtra(PlaybackService.EXTRA_COMMAND_TOKEN, 42)
        assertFalse(PlaybackService.isAuthorizedPlaybackCommand(wrongType, "process-token"))
    }
}
