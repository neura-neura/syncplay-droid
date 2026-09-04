package dev.neura.syncplay.player.mpv

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MpvTrackModelInstrumentedTest {
    @Test
    fun embeddedAndExternalTracksExposeStableKeys() {
        val snapshot = MpvTrackSnapshot(
            tracks = listOf(
                MpvTrackInfo(id = 7, type = MpvTrackType.VIDEO, codec = "hevc", width = 3_840, height = 2_160),
                MpvTrackInfo(id = 2, type = MpvTrackType.AUDIO, codec = "aac", language = "en"),
                MpvTrackInfo(id = 9, type = MpvTrackType.SUBTITLE, codec = "subrip", language = "es"),
                MpvTrackInfo(
                    id = 11,
                    type = MpvTrackType.SUBTITLE,
                    codec = "subrip",
                    externalId = "syncplay-external-subtitle:sub.srt",
                    label = "External",
                ),
            ),
            selectedAudioId = 2,
            selectedVideoId = 7,
            selectedSubtitleId = 11,
        )

        assertEquals("mpv:video:7", snapshot.tracks[0].stableId)
        assertEquals("syncplay-external-subtitle:sub.srt", snapshot.tracks[3].stableId)
        assertEquals(11, snapshot.selectedId(MpvTrackType.SUBTITLE))
        assertEquals(4, MpvTrackMapper.sorted(snapshot).size)
    }

    @Test
    fun stableKeysRoundTripToNativeIds() {
        val snapshot = MpvTrackSnapshot(
            tracks = listOf(MpvTrackInfo(id = 42, type = MpvTrackType.SUBTITLE, codec = "srt")),
        )
        val id = snapshot.tracks.single().stableId
        assertEquals(42, snapshot.engineTrackId(id))
        assertEquals(42, snapshot.engineTrackId("mpv:subtitle:42"))
        assertEquals(null, snapshot.engineTrackId("unrelated"))
    }

    @Test
    fun codecMetadataRemainsAvailableWithoutPlaybackFrameworkTypes() {
        val video = MpvTrackInfo(id = 1, type = MpvTrackType.VIDEO, codec = "hevc")
        val subtitle = MpvTrackInfo(id = 2, type = MpvTrackType.SUBTITLE, codec = "ass")
        assertEquals("video/hevc", MpvTrackMapper.mimeTypeFor(video))
        assertEquals("text/x-ssa", MpvTrackMapper.mimeTypeFor(subtitle))
        assertTrue(MpvTrackMapper.sorted(MpvTrackSnapshot(listOf(video, subtitle))).isNotEmpty())
    }
}
