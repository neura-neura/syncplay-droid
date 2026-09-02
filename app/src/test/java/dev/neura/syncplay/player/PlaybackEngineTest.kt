package dev.neura.syncplay.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackEngineTest {
    @Test
    fun automaticUsesVlcForMatroskaDisplayNameFromContentProvider() {
        val selected = selectPlaybackEngine(
            preference = PlaybackEnginePreference.AUTOMATIC,
            displayName = "Movie.Main10.MKV",
            mimeType = "application/octet-stream",
            uriPath = "document/42",
        )

        assertEquals(PlaybackEngine.VLC, selected.first)
        assertEquals(PlaybackEngineReason.MATROSKA_COMPATIBILITY, selected.second)
    }

    @Test
    fun automaticUsesMedia3ForNonMatroskaMedia() {
        val selected = selectPlaybackEngine(
            preference = PlaybackEnginePreference.AUTOMATIC,
            displayName = "movie.mp4",
            mimeType = "video/mp4",
            uriPath = "/movie.mp4",
        )

        assertEquals(PlaybackEngine.MEDIA3, selected.first)
        assertEquals(PlaybackEngineReason.DEFAULT, selected.second)
    }

    @Test
    fun explicitPreferenceOverridesContainerHeuristic() {
        assertEquals(
            PlaybackEngine.MEDIA3,
            selectPlaybackEngine(PlaybackEnginePreference.MEDIA3, "movie.mkv", null, "/movie.mkv").first,
        )
        assertEquals(
            PlaybackEngine.VLC,
            selectPlaybackEngine(PlaybackEnginePreference.VLC, "movie.mp4", "video/mp4", "/movie.mp4").first,
        )
    }

    @Test
    fun matroskaDetectionUsesMimeOrExtensionWithoutMatchingSimilarSuffixes() {
        assertTrue(isMatroskaMedia(null, "video/x-matroska", "/movie.bin"))
        assertTrue(isMatroskaMedia(null, null, "/movie.mka"))
        assertFalse(isMatroskaMedia(null, null, "/movie.mkv.tmp"))
    }
}
