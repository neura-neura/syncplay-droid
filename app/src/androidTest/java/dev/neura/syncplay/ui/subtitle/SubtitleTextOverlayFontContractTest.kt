package dev.neura.syncplay.ui.subtitle

import android.graphics.Typeface
import android.graphics.Paint
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.neura.syncplay.ui.RemoteSubtitleFontLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * Visual contract for the downloaded-family hand-off into [SubtitleTextOverlay].
 *
 * The production loader retrieves and caches GothamPro; the APK intentionally does not package
 * those font binaries. If the overlay ignores the registered family and falls back to sans-serif,
 * the two captured panes become pixel-identical and the assertion fails.
 */
@RunWith(AndroidJUnit4::class)
class SubtitleTextOverlayFontContractTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun downloadedGothamFamilyChangesOverlayPixelsAgainstSansFallback() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val networkOptIn = InstrumentationRegistry.getArguments()
            .getString(REMOTE_FONT_NETWORK_ARGUMENT)
            ?.equals("true", ignoreCase = true) == true
        assumeTrue(
            "Set $REMOTE_FONT_NETWORK_ARGUMENT=true to validate the GothamPro visual contract",
            networkOptIn,
        )
        val loaded = RemoteSubtitleFontLoader.load(
            context,
            RemoteSubtitleFontLoader.DEFAULT_CSS_URL,
        ).getOrElse { error ->
            throw AssertionError("Default Gotham CSS could not be loaded", error)
        }
        loaded.register()
        assertTrue(loaded.availableWeights("GothamPro").contains(500))
        val resolvedGotham = AtomicReference<Typeface?>()
        val resolvedFallback = AtomicReference<Typeface?>()

        val appearance = SubtitleAppearance.DEFAULT.copy(
            fontFamily = SubtitleAppearance.DEFAULT_FONT_FAMILY,
            fontSize = 32f,
            fontWeight = 500,
            backgroundOpacity = 1f,
            backgroundColor = "#000000",
            textColor = "#FFFFFF",
            bottomOffset = 0f,
            paddingX = 0f,
            paddingY = 0f,
            borderRadius = 0f,
            lineHeight = 1f,
            letterSpacing = 0f,
            textShadow = false,
            useCustomMaxWidth = true,
            maxWidth = 100f,
        )

        composeRule.setContent {
            val resolver = LocalFontFamilyResolver.current
            val gothamTypeface = resolver.resolve(
                fontFamily = SubtitleFontRegistry.resolve(
                    SubtitleAppearance.DEFAULT_FONT_FAMILY,
                    weight = 500,
                ),
                fontWeight = FontWeight.Medium,
                fontStyle = FontStyle.Normal,
                fontSynthesis = FontSynthesis.All,
            ).value as? Typeface
            val fallbackTypeface = resolver.resolve(
                fontFamily = SubtitleFontRegistry.resolve("sans-serif"),
                fontWeight = FontWeight.Medium,
                fontStyle = FontStyle.Normal,
                fontSynthesis = FontSynthesis.All,
            ).value as? Typeface
            SideEffect {
                resolvedGotham.set(gothamTypeface)
                resolvedFallback.set(fallbackTypeface)
            }
            Column {
                Box(
                    modifier = Modifier
                        .size(360.dp, 120.dp)
                        .background(Color.Black)
                        .testTag(GOTHAM_TAG),
                ) {
                    SubtitleTextOverlay(
                        text = PROBE_TEXT,
                        appearance = appearance,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Box(
                    modifier = Modifier
                        .size(360.dp, 120.dp)
                        .background(Color.Black)
                        .testTag(FALLBACK_TAG),
                ) {
                    SubtitleTextOverlay(
                        text = PROBE_TEXT,
                        appearance = appearance.copy(fontFamily = "sans-serif"),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000L) {
            resolvedGotham.get() != null && resolvedFallback.get() != null
        }
        val gothamTypeface = checkNotNull(resolvedGotham.get())
        val fallbackTypeface = checkNotNull(resolvedFallback.get())
        val gothamWidth = Paint().apply {
            typeface = gothamTypeface
            textSize = 64f
        }.measureText(PROBE_TEXT)
        val fallbackWidth = Paint().apply {
            typeface = fallbackTypeface
            textSize = 64f
        }.measureText(PROBE_TEXT)
        assertTrue(
            "Compose resolved GothamPro to fallback metrics on API ${Build.VERSION.SDK_INT}",
            abs(gothamWidth - fallbackWidth) >= MIN_WIDTH_DIFFERENCE_PX,
        )

        // captureToImage uses PixelCopy, which is unavailable on the app's API 23 floor. The
        // resolver/metrics assertion above covers that platform; newer devices additionally prove
        // that the complete SubtitleTextOverlay produces visibly different pixels.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        composeRule.waitForIdle()
        val gothamImage = composeRule.onNodeWithTag(GOTHAM_TAG).captureToImage()
        val fallbackImage = composeRule.onNodeWithTag(FALLBACK_TAG).captureToImage()
        assertEquals(gothamImage.width, fallbackImage.width)
        assertEquals(gothamImage.height, fallbackImage.height)

        val gothamPixels = gothamImage.toPixelMap().buffer
        val fallbackPixels = fallbackImage.toPixelMap().buffer
        val differentPixels = gothamPixels.indices.count { index ->
            gothamPixels[index] != fallbackPixels[index]
        }

        assertTrue(
            "The GothamPro overlay rendered identically to sans-serif; " +
            "the downloaded face was not consumed",
            differentPixels >= MIN_DIFFERENT_PIXELS,
        )
    }

    private companion object {
        const val GOTHAM_TAG = "subtitle-overlay-gotham"
        const val FALLBACK_TAG = "subtitle-overlay-sans-fallback"
        const val MIN_DIFFERENT_PIXELS = 32
        const val MIN_WIDTH_DIFFERENCE_PX = 1f
        const val PROBE_TEXT = "WMWM 0123"
        const val REMOTE_FONT_NETWORK_ARGUMENT = "remoteFontNetwork"
    }
}
