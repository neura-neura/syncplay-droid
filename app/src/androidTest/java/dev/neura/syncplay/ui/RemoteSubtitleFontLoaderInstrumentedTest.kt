package dev.neura.syncplay.ui

import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in integration check for the pinned Gotham CSS endpoint.
 *
 * Network/CDN availability is intentionally not part of the default instrumentation suite; pass
 * `-e remoteFontNetwork true` (or the equivalent Gradle runner argument) to exercise this test.
 */
@RunWith(AndroidJUnit4::class)
class RemoteSubtitleFontLoaderInstrumentedTest {
    @Test
    fun defaultGothamUrlIsRestoredUnlessACustomStylesheetWasSaved() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        try {
            RemoteSubtitleFontLoader.clearSavedCss(context)
            assertEquals(
                RemoteSubtitleFontLoader.DEFAULT_CSS_URL,
                RemoteSubtitleFontLoader.savedCssUrl(context),
            )

            RemoteSubtitleFontLoader.saveCssUrl(context, RemoteSubtitleFontLoader.DEFAULT_CSS_URL)
            assertEquals(
                RemoteSubtitleFontLoader.DEFAULT_CSS_URL,
                RemoteSubtitleFontLoader.savedCssUrl(context),
            )

            val customUrl = "https://example.test/subtitles.css"
            RemoteSubtitleFontLoader.saveCssUrl(context, customUrl)
            assertEquals(customUrl, RemoteSubtitleFontLoader.savedCssUrl(context))

            RemoteSubtitleFontLoader.clearSavedCss(context)
            assertEquals(
                RemoteSubtitleFontLoader.DEFAULT_CSS_URL,
                RemoteSubtitleFontLoader.savedCssUrl(context),
            )
        } finally {
            RemoteSubtitleFontLoader.clearSavedCss(context)
        }
    }

    @Test
    fun defaultGothamCssResolvesRegisteredFamilyAndRealTrueTypeFiles() {
        val networkOptIn = InstrumentationRegistry.getArguments()
            .getString(REMOTE_FONT_NETWORK_ARGUMENT)
            ?.equals("true", ignoreCase = true) == true
        assumeTrue(
            "Set $REMOTE_FONT_NETWORK_ARGUMENT=true to run the external CDN check",
            networkOptIn,
        )

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val loaded = RemoteSubtitleFontLoader.load(
            context,
            RemoteSubtitleFontLoader.DEFAULT_CSS_URL,
        ).getOrElse { error ->
            throw AssertionError("Default Gotham CSS could not be loaded", error)
        }
        // Loading is side-effect free until the caller accepts the result. Mirror the ViewModel's
        // acceptance step so this integration check also exercises the registry hand-off.
        loaded.register()

        assertTrue(
            "The default CSS did not expose GothamPro",
            loaded.familyNames.any { it.equals("GothamPro", ignoreCase = true) },
        )
        assertEquals(setOf(300, 400, 500, 700, 900), loaded.availableWeights("GothamPro"))
        assertEquals(
            setOf(300, 400, 500, 700, 900),
            loaded.availableWeights("GothamPro", italic = true),
        )

        val ttfFiles = loaded.files.filter {
            it.isFile && it.extension.equals("ttf", ignoreCase = true)
        }
        assertTrue("The default CSS did not produce any TTF files", ttfFiles.isNotEmpty())

        ttfFiles.forEach { file ->
            assertTrue("Downloaded TTF is empty: ${file.name}", file.length() > 0L)
            val typeface = runCatching { Typeface.createFromFile(file) }.getOrElse { error ->
                throw AssertionError("Android could not parse downloaded TTF ${file.name}", error)
            }
            assertTrue(
                "${file.name} resolved to Typeface.DEFAULT instead of a custom face",
                typeface != Typeface.DEFAULT,
            )
        }

        val cached = RemoteSubtitleFontLoader.loadCached(
            context,
            RemoteSubtitleFontLoader.DEFAULT_CSS_URL,
        ).getOrElse { error ->
            throw AssertionError("GothamPro could not be restored from the private offline cache", error)
        }
        assertEquals(loaded.availableWeights("GothamPro"), cached.availableWeights("GothamPro"))
        assertEquals(
            loaded.availableWeights("GothamPro", italic = true),
            cached.availableWeights("GothamPro", italic = true),
        )
    }

    private companion object {
        const val REMOTE_FONT_NETWORK_ARGUMENT = "remoteFontNetwork"
    }
}
