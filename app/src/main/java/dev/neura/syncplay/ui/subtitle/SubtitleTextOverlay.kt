package dev.neura.syncplay.ui.subtitle

import android.graphics.Typeface
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import kotlin.math.roundToInt
import androidx.core.text.HtmlCompat

/**
 * Draws a subtitle over the caller's video surface.
 *
 * The component deliberately has no playback-framework dependency: callers provide the currently
 * visible text and can place it in any player backend. The 15 persisted appearance fields are
 * applied here; [SubtitleAppearance.borderRadius] has no MPV equivalent but is rendered by this
 * Compose overlay. Noir's CSS pixels map to Android density-independent pixels; converting those
 * dp values to TextUnit also keeps the chosen size stable when the system font scale changes.
 */
@Composable
fun SubtitleTextOverlay(
    text: String,
    appearance: SubtitleAppearance,
    modifier: Modifier = Modifier,
) {
    if (text.isBlank()) return

    val style = appearance.normalized()
    val density = LocalDensity.current
    val styledText = remember(text) { subtitleHtmlToAnnotatedString(text) }
    val shadowText = remember(text) {
        subtitleHtmlToAnnotatedString(text.replace(Regex("</?font[^>]*>", RegexOption.IGNORE_CASE), ""))
    }
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val availableWidth = maxWidth.takeIf { it.value.isFinite() } ?: 360.dp
        val contentMaxWidth = if (style.useCustomMaxWidth) {
            (availableWidth * (style.maxWidth / 100f).coerceIn(0.1f, 1f))
                .coerceAtMost(availableWidth)
        } else {
            availableWidth * 0.92f
        }
        val bottomPadding = maxHeight
            .takeIf { it.value.isFinite() }
            ?.times((style.bottomOffset / 100f).coerceIn(0f, 1f))
            ?: 0.dp
        val textColor = subtitleComposeColor(style.textColor, Color.White)
        val backgroundColor = subtitleComposeColor(style.backgroundColor, Color.Black)
        val shape = RoundedCornerShape(style.borderRadius.dp)
        val fontSize = with(density) { style.fontSize.dp.toSp() }
        val letterSpacing = with(density) { style.letterSpacing.dp.toSp() }
        val baseTextStyle = TextStyle(
            color = textColor,
            fontSize = fontSize,
            fontWeight = FontWeight(style.fontWeight.coerceIn(1, 1_000)),
            fontFamily = SubtitleFontRegistry.resolve(style.fontFamily),
            lineHeight = style.lineHeight.em,
            letterSpacing = letterSpacing,
            textAlign = TextAlign.Center,
        )
        val containerModifier = Modifier
            .then(
                if (style.useCustomMaxWidth) {
                    Modifier.width(contentMaxWidth)
                } else {
                    Modifier.widthIn(max = contentMaxWidth)
                },
            )
            .clip(shape)
            .background(backgroundColor.copy(alpha = style.backgroundOpacity), shape)
            .padding(horizontal = style.paddingX.dp, vertical = style.paddingY.dp)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = bottomPadding),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(modifier = containerModifier, contentAlignment = Alignment.Center) {
                if (style.textShadow) {
                    // Two transparent text layers provide a broad glow and a tight edge without
                    // stacking the caption background three times.
                    Text(
                        text = shadowText,
                        modifier = Modifier.clearAndSetSemantics { },
                        style = baseTextStyle.copy(
                            color = Color.Transparent,
                            shadow = Shadow(color = Color.Black.copy(alpha = 0.72f), blurRadius = 18f),
                        ),
                    )
                    Text(
                        text = shadowText,
                        modifier = Modifier.clearAndSetSemantics { },
                        style = baseTextStyle.copy(
                            color = Color.Transparent,
                            shadow = Shadow(color = Color.Black.copy(alpha = 0.98f), blurRadius = 6f),
                        ),
                    )
                }
                Text(text = styledText, style = baseTextStyle)
            }
        }
    }
}

private fun subtitleHtmlToAnnotatedString(value: String) = buildAnnotatedString {
    val html = value.replace("\n", "<br>")
    val spanned = HtmlCompat.fromHtml(
        html,
        HtmlCompat.FROM_HTML_MODE_LEGACY or HtmlCompat.FROM_HTML_OPTION_USE_CSS_COLORS,
    )
    append(spanned.toString())
    spanned.getSpans(0, spanned.length, Any::class.java).forEach { span ->
        val start = spanned.getSpanStart(span).coerceAtLeast(0)
        val end = spanned.getSpanEnd(span).coerceAtMost(spanned.length)
        if (start >= end) return@forEach
        val spanStyle = when (span) {
            is ForegroundColorSpan -> SpanStyle(color = Color(span.foregroundColor))
            is UnderlineSpan -> SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)
            is StyleSpan -> when (span.style) {
                Typeface.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
                Typeface.ITALIC -> SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                Typeface.BOLD_ITALIC -> SpanStyle(
                    fontWeight = FontWeight.Bold,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                )
                else -> null
            }
            else -> null
        }
        if (spanStyle != null) addStyle(spanStyle, start, end)
    }
}

private fun subtitleComposeColor(value: String, fallback: Color): Color {
    val normalized = normalizeSubtitleHexColor(value, fallback.toHexRgb())
        .removePrefix("#")
    val red = normalized.substring(0, 2).toIntOrNull(16) ?: fallback.redByte()
    val green = normalized.substring(2, 4).toIntOrNull(16) ?: fallback.greenByte()
    val blue = normalized.substring(4, 6).toIntOrNull(16) ?: fallback.blueByte()
    return Color(red / 255f, green / 255f, blue / 255f, 1f)
}

private fun Color.toHexRgb(): String = "#%02X%02X%02X".format(
    (red * 255f).roundToInt().coerceIn(0, 255),
    (green * 255f).roundToInt().coerceIn(0, 255),
    (blue * 255f).roundToInt().coerceIn(0, 255),
)

private fun Color.redByte(): Int = (red * 255f).roundToInt().coerceIn(0, 255)

private fun Color.greenByte(): Int = (green * 255f).roundToInt().coerceIn(0, 255)

private fun Color.blueByte(): Int = (blue * 255f).roundToInt().coerceIn(0, 255)
