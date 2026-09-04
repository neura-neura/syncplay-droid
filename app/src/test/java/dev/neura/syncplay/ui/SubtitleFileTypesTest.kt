package dev.neura.syncplay.ui

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
        assertEquals(SubtitleFileType.ZIP, SubtitleFileTypes.typeForName("captions.ZIP"))
        assertEquals(SubtitleFileType.SUP, SubtitleFileTypes.typeForName("movie.sup"))
        assertEquals(SubtitleFileType.PGS, SubtitleFileTypes.typeForName("movie.pgs"))
        assertNull(SubtitleFileTypes.typeForName("movie.idx"))
        assertNull(SubtitleFileTypes.typeForName("movie.sub"))
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
            SubtitleFileType.ASS.mimeType,
            SubtitleFileTypes.mimeTypeFor("movie.ass", "audio/aac"),
        )
        assertEquals(
            SubtitleFileType.SRT.mimeType,
            SubtitleFileTypes.mimeTypeFor("movie.srt", "text/plain; charset=utf-8"),
        )
        assertEquals(
            SubtitleFileType.VTT.mimeType,
            SubtitleFileTypes.mimeTypeFor("movie.vtt", "application/octet-stream"),
        )
    }

    @Test
    fun knownProviderMimeCanBeUsedOnlyAsExplicitFallback() {
        assertEquals(
            SubtitleFileType.ASS.mimeType,
            SubtitleFileTypes.mimeTypeFor("unknown", "text/x-ssa"),
        )
        assertNull(SubtitleFileTypes.mimeTypeFor("unknown", "audio/aac"))
        assertNull(SubtitleFileTypes.mimeTypeFor("unknown", null))
    }
}
