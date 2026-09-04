package dev.neura.syncplay.ui

import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteSubtitleFontLoaderTest {
    @Test
    fun prefersTrueTypeBeforeOpenTypeCandidates() {
        assertTrue(
            RemoteSubtitleFontLoader.sourceFormatRank("fonts/GothamPro.otf") >
                RemoteSubtitleFontLoader.sourceFormatRank("fonts/GothamPro.ttf"),
        )
        assertTrue(
            RemoteSubtitleFontLoader.sourceFormatRank("fonts/GothamPro.ttf?cache=1") <
                RemoteSubtitleFontLoader.sourceFormatRank("fonts/GothamPro.otf#regular"),
        )
    }

    @Test
    fun ignoresUnsupportedCssFormatsWhenSelectingAndroidSources() {
        assertTrue(
            RemoteSubtitleFontLoader.sourceFormatRank("fonts/GothamPro.woff2") == Int.MAX_VALUE,
        )
    }
}
