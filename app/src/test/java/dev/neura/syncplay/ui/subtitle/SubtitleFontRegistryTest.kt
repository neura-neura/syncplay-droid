package dev.neura.syncplay.ui.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleFontRegistryTest {
    @Test
    fun normalizesCssFontStacksForRegistryLookup() {
        assertEquals(
            listOf("gothampro", "sans-serif"),
            SubtitleFontRegistry.familyCandidates("\"GothamPro\", sans-serif"),
        )
        assertEquals(
            listOf("noto sans", "serif"),
            SubtitleFontRegistry.familyCandidates(" 'Noto Sans' , serif "),
        )
    }

    @Test
    fun removesDuplicateFamilyTokensWithoutChangingPriority() {
        assertEquals(
            listOf("gothampro", "sans-serif"),
            SubtitleFontRegistry.familyCandidates("GothamPro, sans-serif, GothamPro"),
        )
    }

    @Test
    fun canonicalizesBothPersistedGothamSpellings() {
        assertTrue(SubtitleFontRegistry.isGothamPro(SubtitleAppearance.DEFAULT_FONT_FAMILY))
        assertTrue(SubtitleFontRegistry.isGothamPro("Gotham Pro"))
        assertEquals(
            listOf("gothampro", "sans-serif"),
            SubtitleFontRegistry.familyCandidates("Gotham Pro, sans-serif"),
        )
    }
}
