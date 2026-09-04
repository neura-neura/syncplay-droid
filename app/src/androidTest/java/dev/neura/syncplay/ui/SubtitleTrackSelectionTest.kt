package dev.neura.syncplay.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.neura.syncplay.player.mpv.MpvTrackInfo
import dev.neura.syncplay.player.mpv.MpvTrackMapper
import dev.neura.syncplay.player.mpv.MpvTrackSnapshot
import dev.neura.syncplay.player.mpv.MpvTrackType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SubtitleTrackSelectionTest {
    @Test
    fun embeddedAndExternalSubtitleStreamsExposeStableUiKeys() {
        val snapshot = MpvTrackSnapshot(
            tracks = listOf(
                MpvTrackInfo(id = 7, type = MpvTrackType.VIDEO, codec = "hevc"),
                MpvTrackInfo(id = 3, type = MpvTrackType.SUBTITLE, codec = "pgs", language = "jpn"),
                MpvTrackInfo(
                    id = 8,
                    type = MpvTrackType.SUBTITLE,
                    codec = "ass",
                    externalId = "syncplay-external-subtitle:dialogue.ass",
                    label = "dialogue.ass",
                ),
            ),
            selectedSubtitleId = 8,
        )

        val subtitles = MpvTrackMapper.sorted(snapshot).filter { it.type == MpvTrackType.SUBTITLE }

        assertEquals(2, subtitles.size)
        assertEquals("mpv:subtitle:3", subtitles[0].stableId)
        assertEquals("syncplay-external-subtitle:dialogue.ass", subtitles[1].stableId)
        assertEquals(8, snapshot.selectedId(MpvTrackType.SUBTITLE))
        assertTrue(subtitles[1].label?.contains("dialogue") == true)
    }

    @Test
    fun subtitleSelectionCanBeClearedWithTheNativeOffId() {
        val snapshot = MpvTrackSnapshot(
            tracks = listOf(MpvTrackInfo(id = 3, type = MpvTrackType.SUBTITLE, codec = "subrip")),
            selectedSubtitleId = 3,
        )

        val cleared = snapshot.copy(selectedSubtitleId = -1)

        assertEquals(-1, cleared.selectedId(MpvTrackType.SUBTITLE))
        assertFalse(cleared.tracks.any { it.id == cleared.selectedSubtitleId })
    }
}
