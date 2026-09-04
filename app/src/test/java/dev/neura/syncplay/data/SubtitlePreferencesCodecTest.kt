package dev.neura.syncplay.data

import dev.neura.syncplay.ui.subtitle.SubtitleAppearance
import dev.neura.syncplay.ui.subtitle.SubtitlePreferences
import dev.neura.syncplay.ui.subtitle.SubtitleSyncSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitlePreferencesCodecTest {
    @Test
    fun migratesUntouchedLegacyThirtyEightPointPresetToTwentySeven() {
        val legacy = SubtitlePreferences.DEFAULT.copy(
            appearance = SubtitleAppearance.DEFAULT.copy(fontSize = 38f),
        )
        val unversioned = SubtitlePreferencesCodec.encode(legacy).toMutableMap().apply {
            remove(SubtitlePreferencesCodec.APPEARANCE_PRESET_VERSION_KEY)
        }

        val migrated = SubtitlePreferencesCodec.decode(unversioned)

        assertEquals(27f, migrated.appearance.fontSize)
    }

    @Test
    fun migratesLegacyGothamNameWithoutFallbackToTwentySeven() {
        val legacy = SubtitlePreferences.DEFAULT.copy(
            appearance = SubtitleAppearance.DEFAULT.copy(
                fontSize = 38f,
                fontFamily = "GothamPro",
            ),
        )
        val unversioned = SubtitlePreferencesCodec.encode(legacy).toMutableMap().apply {
            remove(SubtitlePreferencesCodec.APPEARANCE_PRESET_VERSION_KEY)
        }

        val migrated = SubtitlePreferencesCodec.decode(unversioned)

        assertEquals(27f, migrated.appearance.fontSize)
        assertEquals("GothamPro", migrated.appearance.fontFamily)
    }

    @Test
    fun preservesExplicitThirtyEightPointPresetOnceVersioned() {
        val explicit = SubtitlePreferences.DEFAULT.copy(
            appearance = SubtitleAppearance.DEFAULT.copy(fontSize = 38f),
        )

        val decoded = SubtitlePreferencesCodec.decode(SubtitlePreferencesCodec.encode(explicit))

        assertEquals(38f, decoded.appearance.fontSize)
    }

    @Test
    fun encodeAndDecodeRoundTripUsesNormalizedValues() {
        val source = SubtitlePreferences(
            appearance = SubtitleAppearance(
                fontSize = 999f,
                textColor = "abc",
                backgroundColor = "#112233",
                backgroundOpacity = 2f,
                bottomOffset = -10f,
                fontWeight = 950,
                fontFamily = "  Inter  ",
            ),
            sync = SubtitleSyncSettings(offsetMs = 999_999L, rememberOffset = true),
        )

        val encoded = SubtitlePreferencesCodec.encode(source)
        val decoded = SubtitlePreferencesCodec.decode(encoded)

        assertEquals(source.normalized(), decoded)
        assertEquals(200f, encoded[SubtitlePreferencesCodec.FONT_SIZE_KEY])
        assertEquals("#AABBCC", encoded[SubtitlePreferencesCodec.TEXT_COLOR_KEY])
        assertEquals(SubtitleSyncSettings.MAX_OFFSET_MS, encoded[SubtitlePreferencesCodec.OFFSET_MS_KEY])
    }

    @Test
    fun malformedValuesFallBackBeforeNormalization() {
        val decoded = SubtitlePreferencesCodec.decode(
            mapOf(
                SubtitlePreferencesCodec.FONT_SIZE_KEY to "not-a-number",
                SubtitlePreferencesCodec.TEXT_COLOR_KEY to "not-a-color",
                SubtitlePreferencesCodec.BACKGROUND_OPACITY_KEY to Float.NaN,
                SubtitlePreferencesCodec.BOTTOM_OFFSET_KEY to -20f,
                SubtitlePreferencesCodec.FONT_WEIGHT_KEY to 1_200,
                SubtitlePreferencesCodec.OFFSET_MS_KEY to -999_999L,
                SubtitlePreferencesCodec.REMEMBER_OFFSET_KEY to "true",
            ),
        )

        assertEquals(SubtitleAppearance.DEFAULT.fontSize, decoded.appearance.fontSize)
        assertEquals(SubtitleAppearance.DEFAULT.textColor, decoded.appearance.textColor)
        assertEquals(SubtitleAppearance.DEFAULT.backgroundOpacity, decoded.appearance.backgroundOpacity)
        assertEquals(0f, decoded.appearance.bottomOffset)
        assertEquals(900, decoded.appearance.fontWeight)
        assertEquals(SubtitleSyncSettings.MIN_OFFSET_MS, decoded.sync.offsetMs)
        assertTrue(decoded.sync.rememberOffset)
    }
}
