package dev.neura.syncplay.player.vlc

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VlcSourceUriInstrumentedTest {
    @Test
    fun subtitleNamesMatchLabelsAndDecodedFileNames() {
        val subtitle = VlcExternalSubtitle(
            id = "sub-1",
            uri = Uri.parse("content://provider/Sub%20Title.srt"),
            label = "Spanish",
        )

        assertTrue(externalTrackMatches(subtitle, "sub-1", null))
        assertTrue(externalTrackMatches(subtitle.copy(id = null, label = null), "Sub Title", null))
    }

    @Test
    fun descriptorMrlUsesMpvFdScheme() {
        assertEquals("fd://42", fdMrlForDescriptor(42))
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
