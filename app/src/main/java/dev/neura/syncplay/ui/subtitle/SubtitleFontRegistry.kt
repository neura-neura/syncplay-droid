package dev.neura.syncplay.ui.subtitle

import android.graphics.Typeface
import androidx.compose.ui.text.font.FontFamily
import java.util.concurrent.ConcurrentHashMap

/** Process-local bridge from downloaded font family names to Compose typefaces. */
object SubtitleFontRegistry {
    private val fonts = ConcurrentHashMap<String, FontFamily>()

    fun register(familyName: String, typeface: Typeface) {
        fonts[familyName.trim().lowercase()] = FontFamily(typeface)
    }

    fun resolve(value: String): FontFamily {
        val key = value.trim().lowercase()
        fonts[key]?.let { return it }
        return when {
            key.contains("serif") && !key.contains("sans") -> FontFamily.Serif
            key.contains("mono") -> FontFamily.Monospace
            key.contains("cursive") -> FontFamily.Cursive
            else -> FontFamily.SansSerif
        }
    }
}
