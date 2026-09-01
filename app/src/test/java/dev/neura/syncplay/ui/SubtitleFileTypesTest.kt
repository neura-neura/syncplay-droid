package dev.neura.syncplay.ui

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubtitleFileTypesTest {
    @Test
    fun recognizesSupportedExtensionsCaseInsensitively() {
        assertEquals(SubtitleFileType.SRT, SubtitleFileTypes.typeForName("movie.SRT"))
        assertEquals(SubtitleFileType.ASS, SubtitleFileTypes.typeForName("movie.ass"))
        assertEquals(SubtitleFileType.SSA, SubtitleFileTypes.typeForName("movie.SsA"))
        assertEquals(SubtitleFileType.VTT, SubtitleFileTypes.typeForName("movie.vtt"))
        assertEquals(SubtitleFileType.TTML, SubtitleFileTypes.typeForName("movie.ttml"))
        assertEquals(SubtitleFileType.XML, SubtitleFileTypes.typeForName("movie.xml"))
    }

    @Test
    fun extractsExtensionFromUriLikeNamesWithoutQueryOrFragment() {
        assertEquals("ass", SubtitleFileTypes.extensionOf("smb://nas/share/My%20Track.ASS?download=1#part"))
        assertEquals("srt", SubtitleFileTypes.extensionOf("C:\\video\\captions.srt"))
        assertNull(SubtitleFileTypes.extensionOf("captions"))
        assertNull(SubtitleFileTypes.extensionOf("captions."))
    }

    @Test
    fun rejectsNonSubtitleExtensionsForPostSelectionValidation() {
        assertNull(SubtitleFileTypes.typeForName("movie.mp4"))
        assertNull(SubtitleFileTypes.typeForName("movie.ass.bak"))
        assertNull(SubtitleFileTypes.typeForName(".ass"))
    }

    @Test
    fun extensionAlwaysWinsOverProviderMime() {
        assertEquals(
            MimeTypes.TEXT_SSA,
            SubtitleFileTypes.mimeTypeFor("movie.ass", "audio/aac"),
        )
        assertEquals(
            MimeTypes.APPLICATION_SUBRIP,
            SubtitleFileTypes.mimeTypeFor("movie.srt", "text/plain; charset=utf-8"),
        )
        assertEquals(
            MimeTypes.TEXT_VTT,
            SubtitleFileTypes.mimeTypeFor("movie.vtt", "application/octet-stream"),
        )
    }

    @Test
    fun knownProviderMimeCanBeUsedOnlyAsExplicitFallback() {
        assertEquals(
            MimeTypes.TEXT_SSA,
            SubtitleFileTypes.mimeTypeFor("unknown", "text/x-ssa"),
        )
        assertNull(SubtitleFileTypes.mimeTypeFor("unknown", "audio/aac"))
        assertNull(SubtitleFileTypes.mimeTypeFor("unknown", null))
    }
}
