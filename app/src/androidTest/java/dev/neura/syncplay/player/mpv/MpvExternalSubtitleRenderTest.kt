package dev.neura.syncplay.player.mpv

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MpvExternalSubtitleRenderTest {
    @Test
    fun externalSubtitleMetadataRetainsFormatAndSelectionIntent() {
        val subtitle = MpvExternalSubtitle(
            id = "sidecar",
            uri = Uri.parse("file:///cache/subtitle.ass"),
            label = "English",
            mimeType = "text/x-ssa",
            language = "en",
            isDefault = true,
        )
        assertTrue(subtitle.isDefault)
        assertEquals("text/x-ssa", subtitle.mimeType)
        assertEquals("en", subtitle.language)
    }
}
