package dev.neura.syncplay.player.vlc

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VlcTrackMapperInstrumentedTest {
    @Test
    fun embeddedAndExternalTracksMapToStableMedia3Groups() {
        val snapshot = VlcTrackSnapshot(
            tracks = listOf(
                VlcTrackInfo(id = 7, type = VlcTrackType.VIDEO, codec = "hevc", width = 3_840, height = 2_160),
                VlcTrackInfo(id = 2, type = VlcTrackType.AUDIO, codec = "aac", language = "en"),
                VlcTrackInfo(id = 9, type = VlcTrackType.TEXT, codec = "subrip", language = "es"),
                VlcTrackInfo(
                    id = 11,
                    type = VlcTrackType.TEXT,
                    codec = "subrip",
                    externalId = "syncplay-external-subtitle:sub.srt",
                    label = "External",
                ),
            ),
            selectedAudioId = 2,
            selectedVideoId = 7,
            selectedTextId = 11,
        )

        val tracks = VlcTrackMapper.toMedia3Tracks(snapshot)
        assertEquals(3, tracks.groups.size)
        val video = tracks.groups.first { it.type == C.TRACK_TYPE_VIDEO }
        assertEquals(MimeTypes.VIDEO_H265, video.getTrackFormat(0).sampleMimeType)
        assertTrue(video.isTrackSelected(0))
        val text = tracks.groups.first { it.type == C.TRACK_TYPE_TEXT }
        assertEquals(2, text.length)
        assertEquals("syncplay-external-subtitle:sub.srt", text.getTrackFormat(1).id)
        assertTrue(text.isTrackSelected(1))
    }

    @Test
    fun formatIdRoundTripsToLibVlcTrackId() {
        val snapshot = VlcTrackSnapshot(
            tracks = listOf(VlcTrackInfo(id = 42, type = VlcTrackType.TEXT, codec = "srt")),
        )
        val id = snapshot.tracks.single().media3FormatId()
        assertEquals(42, snapshot.engineTrackId(id))
        assertEquals(42, snapshot.engineTrackId("vlc:text:42"))
        assertEquals(null, snapshot.engineTrackId("unrelated"))
    }

    @Test
    fun declaredSrtAndAssSidecarMimeTypesRemainSupported() {
        val snapshot = VlcTrackSnapshot(
            tracks = listOf(
                VlcTrackInfo(
                    id = 20,
                    type = VlcTrackType.TEXT,
                    codec = MimeTypes.APPLICATION_SUBRIP,
                    externalId = "syncplay-external-subtitle:srt",
                ),
                VlcTrackInfo(
                    id = 21,
                    type = VlcTrackType.TEXT,
                    codec = MimeTypes.TEXT_SSA,
                    externalId = "syncplay-external-subtitle:ass",
                ),
            ),
        )

        val text = VlcTrackMapper.toMedia3Tracks(snapshot).groups.single()
        assertEquals(MimeTypes.APPLICATION_SUBRIP, text.getTrackFormat(0).sampleMimeType)
        assertEquals(MimeTypes.TEXT_SSA, text.getTrackFormat(1).sampleMimeType)
        assertTrue(text.isTrackSupported(0))
        assertTrue(text.isTrackSupported(1))
    }
}
