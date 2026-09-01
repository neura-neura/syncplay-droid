package dev.neura.syncplay.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackServiceSecurityTest {
    @Test
    fun allowsTheOwningApplicationEvenWhenPlatformTrustIsUnavailable() {
        assertTrue(
            PlaybackService.isAllowedController(
                controllerPackage = "dev.neura.syncplay",
                applicationPackage = "dev.neura.syncplay",
                isTrusted = false,
            ),
        )
    }

    @Test
    fun allowsTrustedSystemOrMediaControllers() {
        assertTrue(
            PlaybackService.isAllowedController(
                controllerPackage = "android",
                applicationPackage = "dev.neura.syncplay",
                isTrusted = true,
            ),
        )
    }

    @Test
    fun rejectsUntrustedForeignControllers() {
        assertFalse(
            PlaybackService.isAllowedController(
                controllerPackage = "com.example.other",
                applicationPackage = "dev.neura.syncplay",
                isTrusted = false,
            ),
        )
    }

    @Test
    fun acceptsOnlyKnownActionsWithTheProcessToken() {
        assertTrue(
            PlaybackService.isAuthorizedPlaybackAction(
                action = PlaybackService.ACTION_PLAY,
                token = "process-token",
                expectedToken = "process-token",
            ),
        )
        assertFalse(
            PlaybackService.isAuthorizedPlaybackAction(
                action = PlaybackService.ACTION_PLAY,
                token = "other-token",
                expectedToken = "process-token",
            ),
        )
        assertFalse(
            PlaybackService.isAuthorizedPlaybackAction(
                action = "com.example.unrelated.ACTION",
                token = "process-token",
                expectedToken = "process-token",
            ),
        )
    }

}
