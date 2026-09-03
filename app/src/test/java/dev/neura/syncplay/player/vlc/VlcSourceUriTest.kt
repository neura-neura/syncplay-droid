package dev.neura.syncplay.player.vlc

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VlcSourceUriTest {
    @Test
    fun subtitleNamesMatchLabelsAndDecodedFileNames() {
        assertTrue(subtitleMetadataMatches("external-1", null, "external-1", "Spanish", "Sub Title.srt"))
        assertTrue(subtitleMetadataMatches("Sub Title", null, null, null, "Sub Title.srt"))
        assertFalse(subtitleMetadataMatches("French", null, null, "Spanish", "Sub Title.srt"))
    }

    @Test
    fun descriptorMrlContainsOnlyTheDescriptorNumber() {
        assertEquals("fd://17", fdMrlForDescriptor(17))
        assertThrows { fdMrlForDescriptor(-1) }
    }

    @Test
    fun userSelectedExternalSubtitleUsesMpvSelectMode() {
        assertEquals("select", mpvSubtitleAddMode(C.SELECTION_FLAG_DEFAULT))
        assertEquals(
            "select",
            mpvSubtitleAddMode(C.SELECTION_FLAG_DEFAULT or C.SELECTION_FLAG_FORCED),
        )
        assertEquals("auto", mpvSubtitleAddMode(0))
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
