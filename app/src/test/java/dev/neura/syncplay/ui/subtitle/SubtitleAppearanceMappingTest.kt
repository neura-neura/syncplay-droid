package dev.neura.syncplay.ui.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleAppearanceMappingTest {
    @Test
    fun mapsNoirAppearanceControlsToMpvProperties() {
        val properties = SubtitleAppearance(
            fontSize = 36f,
            textColor = "#abc",
            backgroundColor = "#112233",
            backgroundOpacity = 0.5f,
            bottomOffset = 12f,
            fontWeight = 700,
            fontFamily = "Inter, sans-serif",
            useCustomMaxWidth = true,
            maxWidth = 70f,
            paddingX = 16f,
            paddingY = 6f,
            borderRadius = 20f,
            lineHeight = 1.5f,
            letterSpacing = 1.2f,
            textShadow = false,
        ).toMpvProperties()

        assertEquals(36f, properties["sub-font-size"])
        assertEquals("Inter", properties["sub-font"])
        assertEquals("#FFAABBCC", properties["sub-color"])
        assertEquals("#80112233", properties["sub-back-color"])
        assertEquals("#80112233", properties["sub-outline-color"])
        assertEquals("#00000000", properties["sub-shadow-color"])
        assertEquals("background-box", properties["sub-border-style"])
        assertEquals(2f, properties["sub-outline-size"])
        assertEquals(0f, properties["sub-shadow-offset"])
        assertEquals(88f, properties["sub-pos"])
        assertEquals(300, properties["sub-margin-x"])
        assertEquals(1.2f, properties["sub-spacing"])
        assertEquals(18f, properties["sub-line-spacing"])
        assertEquals(true, properties["sub-bold"])
        assertFalse(properties.containsKey("sub-border-radius"))
    }

    @Test
    fun mapsRememberedSyncOffsetToSeconds() {
        val properties = SubtitleSyncSettings(offsetMs = 1_500L, rememberOffset = true).toMpvProperties()

        assertEquals(1.5f, properties["sub-delay"])
        assertTrue(properties.containsKey("sub-delay"))
    }

    @Test
    fun resolvesDefaultAndQuotedFontFamilyNames() {
        assertEquals("sans-serif", SubtitleAppearance.DEFAULT.toMpvProperties()["sub-font"])
        assertEquals(
            "Noto Sans",
            SubtitleAppearance(fontFamily = "\"Noto Sans\", sans-serif").toMpvProperties()["sub-font"],
        )
    }
}
