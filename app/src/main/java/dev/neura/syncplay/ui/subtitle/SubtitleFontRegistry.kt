package dev.neura.syncplay.ui.subtitle

import android.graphics.Typeface
import androidx.compose.ui.text.font.FontFamily
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/** Process-local bridge from downloaded/platform font family names to Compose typefaces. */
object SubtitleFontRegistry {
    private data class RegisteredFace(
        val typeface: Typeface,
        val weight: Int,
        val italic: Boolean,
    )

    private val fonts = ConcurrentHashMap<String, List<RegisteredFace>>()

    fun register(
        familyName: String,
        typeface: Typeface,
        weight: Int = 400,
        italic: Boolean = false,
    ) {
        normalizeFamilyToken(familyName).takeIf(String::isNotEmpty)?.let { key ->
            val face = RegisteredFace(typeface, weight.coerceIn(1, 1_000), italic)
            // ConcurrentHashMap.compute is API 24.  A short synchronized replacement preserves
            // the Android 6 (API 23) floor and publishes one immutable face list atomically.
            synchronized(fonts) {
                fonts[key] = fonts[key].orEmpty().filterNot {
                    it.weight == face.weight && it.italic == face.italic
                } + face
            }
        }
    }

    fun unregister(familyName: String) {
        synchronized(fonts) {
            familyCandidates(familyName).forEach { fonts.remove(it) }
        }
    }

    fun resolve(
        value: String,
        weight: Int = 400,
        italic: Boolean = false,
    ): FontFamily {
        val candidates = familyCandidates(value)
        candidates.forEach { key ->
            fonts[key]?.minByOrNull { face ->
                abs(face.weight - weight.coerceIn(1, 1_000)) +
                    if (face.italic == italic) 0 else ITALIC_MISMATCH_PENALTY
            }?.let { return FontFamily(it.typeface) }
        }
        val key = candidates.firstOrNull().orEmpty()
        return when {
            key.contains("serif") && !key.contains("sans") -> FontFamily.Serif
            key.contains("mono") -> FontFamily.Monospace
            key.contains("cursive") -> FontFamily.Cursive
            else -> FontFamily.SansSerif
        }
    }

    /**
     * Splits a CSS font stack into normalized family tokens.  Keeping this pure makes the
     * resolver deterministic and lets a downloaded `GothamPro` face satisfy the default
     * `GothamPro, sans-serif` stack instead of silently selecting SansSerif.
     */
    internal fun familyCandidates(value: String): List<String> = value
        .split(',')
        .asSequence()
        .map(::normalizeFamilyToken)
        .filter(String::isNotEmpty)
        .distinct()
        .toList()

    internal fun registeredFaceWeights(value: String, italic: Boolean = false): Set<Int> =
        familyCandidates(value)
            .flatMap { fonts[it].orEmpty() }
            .filter { it.italic == italic }
            .mapTo(linkedSetOf(), RegisteredFace::weight)

    internal fun isGothamPro(value: String): Boolean = familyCandidates(value).any { it == "gothampro" }

    private fun normalizeFamilyToken(value: String): String {
        val normalized = value
            .trim()
            .removeSurrounding("\"", "\"")
            .removeSurrounding("'", "'")
            .trim()
            .lowercase(Locale.ROOT)
        // Noir and older Syncplay Droid builds used both spellings.  Canonicalizing only this
        // known alias lets either persisted value resolve the downloaded GothamPro faces.
        return if (normalized.replace(" ", "") == "gothampro") "gothampro" else normalized
    }

    private const val ITALIC_MISMATCH_PENALTY = 10_000
}
