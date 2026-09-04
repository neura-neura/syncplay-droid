package dev.neura.syncplay.ui.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleAppearanceMappingTest {
    @Test
    fun defaultFontSizeIsTwentySevenLandscapeReferenceUnits() {
        assertEquals(27f, SubtitleAppearance.DEFAULT.fontSize)
        assertEquals(27f, SubtitleAppearance.DEFAULT.scaledForViewport(1_920f, 1_080f).fontSize)
        assertEquals(27f, SubtitleAppearance.DEFAULT.toMpvProperties()["sub-font-size"])
    }

    @Test
    fun portraitFontSizeShrinksWithTheExactViewportRatio() {
        val appearance = SubtitleAppearance.DEFAULT

        assertEquals(15.1875f, appearance.scaledForViewport(360f, 640f).fontSize, 0.001f)
        assertEquals(10.8f, appearance.scaledForViewport(360f, 900f).fontSize, 0.001f)
        assertEquals(12.15f, appearance.scaledForViewport(1_080f, 2_400f).fontSize, 0.001f)
        assertEquals(27f, appearance.scaledForViewport(640f, 360f).fontSize, 0.001f)
        assertEquals(27f, appearance.scaledForViewport(0f, 0f).fontSize, 0.001f)
    }

    @Test
    fun viewportScaleHandlesFoldableAndInvalidWindowDimensions() {
        assertEquals(0.75f, SubtitleViewport(1_200f, 1_600f).orientationScale, 0.001f)
        assertEquals(0.18f, SubtitleViewport(360f, 2_000f).orientationScale, 0.001f)
        assertEquals(1f, SubtitleViewport(0f, 640f).orientationScale, 0.001f)
        assertEquals(1f, SubtitleViewport(-360f, 640f).orientationScale, 0.001f)
        assertEquals(1f, SubtitleViewport(Float.NaN, 640f).orientationScale, 0.001f)
        assertEquals(1f, SubtitleViewport(360f, Float.POSITIVE_INFINITY).orientationScale, 0.001f)
    }

    @Test
    fun mpvMappingUsesTheSameViewportScale() {
        val properties = SubtitleAppearance(
            fontSize = 36f,
            lineHeight = 1.5f,
        ).toMpvProperties(SubtitleViewport(360f, 640f))

        assertEquals(20.25f, properties["sub-font-size"])
        assertEquals(10.125f, properties["sub-line-spacing"])
    }

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
        assertEquals("GothamPro", SubtitleAppearance.DEFAULT.toMpvProperties()["sub-font"])
        assertEquals(
            "Noto Sans",
            SubtitleAppearance(fontFamily = "\"Noto Sans\", sans-serif").toMpvProperties()["sub-font"],
        )
    }
}
