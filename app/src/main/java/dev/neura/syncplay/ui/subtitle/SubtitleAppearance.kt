package dev.neura.syncplay.ui.subtitle

import java.util.Locale
import kotlin.math.roundToInt

/**
 * The persisted appearance contract for subtitles.
 *
 * The numeric values intentionally mirror Noir Player's CSS controls.  The Android overlay
 * treats pixel values as dp at the composition boundary; the MPV adapter translates the same
 * values into libass properties where a direct equivalent exists.
 */
data class SubtitleAppearance(
    val fontSize: Float = DEFAULT.fontSize,
    val textColor: String = DEFAULT.textColor,
    val backgroundColor: String = DEFAULT.backgroundColor,
    val backgroundOpacity: Float = DEFAULT.backgroundOpacity,
    val bottomOffset: Float = DEFAULT.bottomOffset,
    val fontWeight: Int = DEFAULT.fontWeight,
    val fontFamily: String = DEFAULT.fontFamily,
    val useCustomMaxWidth: Boolean = DEFAULT.useCustomMaxWidth,
    val maxWidth: Float = DEFAULT.maxWidth,
    val paddingX: Float = DEFAULT.paddingX,
    val paddingY: Float = DEFAULT.paddingY,
    val borderRadius: Float = DEFAULT.borderRadius,
    val lineHeight: Float = DEFAULT.lineHeight,
    val letterSpacing: Float = DEFAULT.letterSpacing,
    val textShadow: Boolean = DEFAULT.textShadow,
) {
    /**
     * Normalizes values at every persistence and UI boundary.
     *
     * These are Noir's typed-input bounds (the wider bounds); slider bounds are exposed by
     * [SubtitleAppearanceRanges] and are deliberately narrower for the comfortable UI path.
     */
    fun normalized(): SubtitleAppearance = copy(
        fontSize = finiteOr(fontSize, DEFAULT.fontSize).coerceIn(
            SubtitleAppearanceRanges.fontSizeTyped.min,
            SubtitleAppearanceRanges.fontSizeTyped.max,
        ),
        textColor = normalizeHexColor(textColor, DEFAULT.textColor),
        backgroundColor = normalizeHexColor(backgroundColor, DEFAULT.backgroundColor),
        backgroundOpacity = finiteOr(backgroundOpacity, DEFAULT.backgroundOpacity).coerceIn(0f, 1f),
        bottomOffset = finiteOr(bottomOffset, DEFAULT.bottomOffset).coerceIn(
            SubtitleAppearanceRanges.positionTyped.min,
            SubtitleAppearanceRanges.positionTyped.max,
        ),
        fontWeight = fontWeight.coerceIn(
            SubtitleAppearanceRanges.weightTyped.min.toInt(),
            SubtitleAppearanceRanges.weightTyped.max.toInt(),
        ),
        fontFamily = fontFamily.trim().ifEmpty { DEFAULT.fontFamily },
        maxWidth = finiteOr(maxWidth, DEFAULT.maxWidth).coerceIn(
            SubtitleAppearanceRanges.widthTyped.min,
            SubtitleAppearanceRanges.widthTyped.max,
        ),
        paddingX = finiteOr(paddingX, DEFAULT.paddingX).coerceIn(
            SubtitleAppearanceRanges.paddingXTyped.min,
            SubtitleAppearanceRanges.paddingXTyped.max,
        ),
        paddingY = finiteOr(paddingY, DEFAULT.paddingY).coerceIn(
            SubtitleAppearanceRanges.paddingYTyped.min,
            SubtitleAppearanceRanges.paddingYTyped.max,
        ),
        borderRadius = finiteOr(borderRadius, DEFAULT.borderRadius).coerceIn(
            SubtitleAppearanceRanges.radiusTyped.min,
            SubtitleAppearanceRanges.radiusTyped.max,
        ),
        lineHeight = finiteOr(lineHeight, DEFAULT.lineHeight).coerceIn(
            SubtitleAppearanceRanges.lineHeightTyped.min,
            SubtitleAppearanceRanges.lineHeightTyped.max,
        ),
        letterSpacing = finiteOr(letterSpacing, DEFAULT.letterSpacing).coerceIn(
            SubtitleAppearanceRanges.letterSpacingTyped.min,
            SubtitleAppearanceRanges.letterSpacingTyped.max,
        ),
    )

    companion object {
        /** Noir's default stack; it remains readable through the platform fallback while loading. */
        const val DEFAULT_FONT_FAMILY: String = "GothamPro, sans-serif"

        val DEFAULT: SubtitleAppearance = SubtitleAppearance(
            fontSize = 38f,
            textColor = "#FFFFFF",
            backgroundColor = "#000000",
            backgroundOpacity = 0.23f,
            bottomOffset = 4f,
            fontWeight = 500,
            fontFamily = DEFAULT_FONT_FAMILY,
            useCustomMaxWidth = false,
            maxWidth = 78f,
            paddingX = 12f,
            paddingY = 8f,
            borderRadius = 8f,
            lineHeight = 1.18f,
            letterSpacing = 0.2f,
            textShadow = true,
        )
    }
}

/** Offset controls are independent from appearance and persist with the same preferences file. */
data class SubtitleSyncSettings(
    val offsetMs: Long = 0L,
    val rememberOffset: Boolean = false,
) {
    fun normalized(): SubtitleSyncSettings = copy(
        offsetMs = offsetMs.coerceIn(MIN_OFFSET_MS, MAX_OFFSET_MS),
    )

    companion object {
        const val MIN_OFFSET_MS: Long = -120_000L
        const val MAX_OFFSET_MS: Long = 120_000L
        val DEFAULT: SubtitleSyncSettings = SubtitleSyncSettings()
    }
}

/** One slider's visible bounds and increment. */
data class SubtitleNumericRange(
    val min: Float,
    val max: Float,
    val step: Float,
) {
    init {
        require(min <= max) { "Minimum must not exceed maximum" }
        require(step > 0f && step.isFinite()) { "Step must be finite and positive" }
    }
}

/**
 * Noir's comfortable slider bounds and the wider typed-number bounds.
 *
 * A typed value is clamped on commit.  Persisted values use those same typed bounds, while MPV
 * performs its own narrower safety clamps when a native property has a stricter range.
 */
object SubtitleAppearanceRanges {
    val fontSizeSlider = SubtitleNumericRange(22f, 64f, 1f)
    val fontSizeTyped = SubtitleNumericRange(8f, 200f, 1f)

    val positionSlider = SubtitleNumericRange(2f, 30f, 1f)
    val positionTyped = SubtitleNumericRange(0f, 100f, 1f)

    val opacitySlider = SubtitleNumericRange(0f, 100f, 1f)
    val opacityTyped = opacitySlider

    val weightSlider = SubtitleNumericRange(100f, 900f, 10f)
    val weightTyped = weightSlider

    val widthSlider = SubtitleNumericRange(20f, 100f, 1f)
    val widthTyped = SubtitleNumericRange(10f, 100f, 1f)

    val lineHeightSlider = SubtitleNumericRange(1f, 1.6f, 0.01f)
    val lineHeightTyped = SubtitleNumericRange(0.5f, 3f, 0.01f)

    val letterSpacingSlider = SubtitleNumericRange(-1f, 3f, 0.1f)
    val letterSpacingTyped = SubtitleNumericRange(-5f, 10f, 0.1f)

    val paddingXSlider = SubtitleNumericRange(0f, 36f, 1f)
    val paddingXTyped = SubtitleNumericRange(0f, 80f, 1f)

    val paddingYSlider = SubtitleNumericRange(0f, 24f, 1f)
    val paddingYTyped = SubtitleNumericRange(0f, 80f, 1f)

    val radiusSlider = SubtitleNumericRange(0f, 24f, 1f)
    val radiusTyped = SubtitleNumericRange(0f, 80f, 1f)

    val syncOffsetSlider = SubtitleNumericRange(-120f, 120f, 0.05f)
    val syncOffsetTyped = syncOffsetSlider
}

/** Appearance and sync are stored together so a single DataStore edit is atomic. */
data class SubtitlePreferences(
    val appearance: SubtitleAppearance = SubtitleAppearance.DEFAULT,
    val sync: SubtitleSyncSettings = SubtitleSyncSettings.DEFAULT,
) {
    fun normalized(): SubtitlePreferences = copy(
        appearance = appearance.normalized(),
        sync = sync.normalized(),
    )

    companion object {
        val DEFAULT = SubtitlePreferences()
    }
}

/**
 * Pure MPV/libass property translation.  [SubtitleAppearance.borderRadius] is intentionally not
 * included: MPV/libass has no rounded caption-box equivalent, so radius remains Compose-overlay
 * only.  [SubtitleAppearance.lineHeight] is represented by the additional line-spacing property.
 */
fun SubtitleAppearance.toMpvProperties(): Map<String, Any> {
    val style = normalized()
    val backgroundOpacity = style.backgroundOpacity.coerceIn(0f, 1f)
    val lineSpacing = ((style.lineHeight - 1f) * style.fontSize).coerceIn(-100f, 100f)
    return linkedMapOf(
        "sub-font-size" to style.fontSize.coerceIn(8f, 160f),
        "sub-font" to style.fontFamily.toMpvFontName(),
        "sub-color" to toMpvColor(style.textColor),
        "sub-back-color" to toMpvColor(style.backgroundColor, backgroundOpacity),
        "sub-outline-color" to toMpvColor(
            style.backgroundColor,
            maxOf(backgroundOpacity, 0.35f),
        ),
        "sub-shadow-color" to toMpvColor(
            "#000000",
            if (style.textShadow) 0.78f else 0f,
        ),
        "sub-border-style" to if (backgroundOpacity > 0f) "background-box" else "outline-and-shadow",
        "sub-outline-size" to (style.paddingX / 8f).coerceIn(0f, 16f),
        "sub-shadow-offset" to if (style.textShadow) {
            (style.paddingY / 3f).coerceIn(0f, 12f)
        } else {
            0f
        },
        "sub-pos" to (100f - style.bottomOffset).coerceIn(0f, 100f),
        "sub-margin-x" to if (style.useCustomMaxWidth) {
            ((100f - style.maxWidth) * 10f).roundToInt().coerceIn(0, 1_000)
        } else {
            0
        },
        "sub-spacing" to style.letterSpacing.coerceIn(-10f, 10f),
        "sub-line-spacing" to lineSpacing,
        "sub-bold" to (style.fontWeight >= 600),
    )
}

/** Translate the sync offset to MPV seconds for the native subtitle delay property. */
fun SubtitleSyncSettings.toMpvProperties(): Map<String, Any> = linkedMapOf(
    "sub-delay" to (normalized().offsetMs / 1_000f),
)

/** Exact Noir color encoding: uppercase #AARRGGBB, with clamped alpha and 3/6-digit RGB input. */
fun toMpvColor(hexColor: String, alpha: Float = 1f): String {
    val alphaHex = (alpha.coerceIn(0f, 1f) * 255f)
        .roundToInt()
        .toString(16)
        .padStart(2, '0')
    val rgb = normalizeHexColor(hexColor, "#FFFFFF").removePrefix("#")
    return "#$alphaHex$rgb".uppercase(Locale.ROOT)
}

/** Public normalization helper used by the hex editor and persistence codec. */
fun normalizeSubtitleHexColor(value: String, fallback: String = "#FFFFFF"): String =
    normalizeHexColor(value, fallback)

private fun String.toMpvFontName(): String {
    val trimmed = trim()
    if (trimmed.isEmpty() || trimmed.equals(SubtitleAppearance.DEFAULT_FONT_FAMILY, ignoreCase = true)) {
        return "sans-serif"
    }
    return trimmed
        .substringBefore(',')
        .trim()
        .removeSurrounding("\"", "\"")
        .removeSurrounding("'", "'")
        .ifEmpty { "sans-serif" }
}

private fun finiteOr(value: Float, fallback: Float): Float =
    value.takeIf(Float::isFinite) ?: fallback

private fun normalizeHexColor(value: String, fallback: String): String {
    val raw = value.trim().removePrefix("#")
    val expanded = if (raw.length == 3 && raw.all(::isHexCharacter)) {
        raw.map { "$it$it" }.joinToString("")
    } else {
        raw
    }
    return if (expanded.length == 6 && expanded.all(::isHexCharacter)) {
        "#${expanded.uppercase(Locale.ROOT)}"
    } else {
        fallback.trim().let { fallbackValue ->
            val fallbackRaw = fallbackValue.removePrefix("#")
            if (fallbackRaw.length == 6 && fallbackRaw.all(::isHexCharacter)) {
                "#${fallbackRaw.uppercase(Locale.ROOT)}"
            } else {
                "#FFFFFF"
            }
        }
    }
}

private fun isHexCharacter(value: Char): Boolean = value.digitToIntOrNull(16) != null
