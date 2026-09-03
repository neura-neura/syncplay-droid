package dev.neura.syncplay.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackEngineTest {
    @Test
    fun automaticUsesMpvForMatroskaDisplayNameFromContentProvider() {
        val selected = selectPlaybackEngine(
            preference = PlaybackEnginePreference.AUTOMATIC,
            displayName = "Movie.Main10.MKV",
            mimeType = "application/octet-stream",
            uriPath = "content://provider/document/42",
            sourceAccess = SourceAccessClassification.SEEKABLE,
        )

        assertEquals(PlaybackEngine.MPV, selected.first)
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
            PlaybackEngine.MPV,
            selectPlaybackEngine(PlaybackEnginePreference.MPV, "movie.mp4", "video/mp4", "/movie.mp4").first,
        )
    }

    @Test
    fun directSmbKeepsSeekableMedia3DataSourceEvenWhenMpvWasRequested() {
        val selected = selectPlaybackEngine(
            PlaybackEnginePreference.MPV,
            "movie.mkv",
            "video/x-matroska",
            "syncplaysmb://profile/share/movie.mkv",
        )

        assertEquals(PlaybackEngine.MEDIA3, selected.first)
        assertEquals(PlaybackEngineReason.DIRECT_SMB_COMPATIBILITY, selected.second)
    }

    @Test
    fun sequentialDocumentProviderFallsBackToMedia3InsteadOfGivingMpvAnUnseekablePipe() {
        val selected = selectPlaybackEngine(
            PlaybackEnginePreference.AUTOMATIC,
            "movie.mkv",
            "video/x-matroska",
            "content://provider/movie",
            SourceAccessClassification.SEQUENTIAL,
        )

        assertEquals(PlaybackEngine.MEDIA3, selected.first)
        assertEquals(PlaybackEngineReason.SEQUENTIAL_PROVIDER_COMPATIBILITY, selected.second)
    }

    @Test
    fun automaticDoesNotAssumeAnUnknownDocumentProviderIsSeekable() {
        val selected = selectPlaybackEngine(
            PlaybackEnginePreference.AUTOMATIC,
            "movie.mkv",
            "video/x-matroska",
            "content://provider/movie",
            SourceAccessClassification.UNKNOWN,
        )

        assertEquals(PlaybackEngine.MEDIA3, selected.first)
        assertEquals(PlaybackEngineReason.UNVERIFIED_PROVIDER_COMPATIBILITY, selected.second)
    }

    @Test
    fun matroskaDetectionUsesMimeOrExtensionWithoutMatchingSimilarSuffixes() {
        assertTrue(isMatroskaMedia(null, "video/x-matroska", "/movie.bin"))
        assertTrue(isMatroskaMedia(null, null, "/movie.mka"))
        assertFalse(isMatroskaMedia(null, null, "/movie.mkv.tmp"))
    }
}
