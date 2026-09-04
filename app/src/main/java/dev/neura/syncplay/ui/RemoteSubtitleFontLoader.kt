package dev.neura.syncplay.ui

import android.content.Context
import android.graphics.Typeface
import dev.neura.syncplay.ui.subtitle.SubtitleFontRegistry
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import kotlin.math.abs

data class LoadedRemoteFontSet(
    val familyNames: List<String>,
    val files: List<File>,
) {
    val primaryFamilyName: String get() = familyNames.first()
}

/** Loads the TTF/OTF faces and families declared by a Noir-compatible @font-face stylesheet. */
object RemoteSubtitleFontLoader {
    const val DEFAULT_CSS_URL =
        "https://cdn.jsdelivr.net/npm/gotham-pro-font@1.0.0/fonts.min.css"
    private const val PREFERENCES = "subtitle_remote_font"
    private const val CSS_URL = "css_url"
    private const val DEFAULT_CSS_DISABLED = "default_css_disabled"
    private const val MAX_CSS_BYTES = 1_048_576
    private const val MAX_FONT_BYTES = 25 * 1_048_576
    private const val MAX_TOTAL_FONT_BYTES = 80 * 1_048_576
    private const val MAX_FONT_FACES = 32

    fun savedCssUrl(context: Context): String {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        if (preferences.contains(CSS_URL)) return preferences.getString(CSS_URL, "").orEmpty()
        return if (preferences.getBoolean(DEFAULT_CSS_DISABLED, false)) "" else DEFAULT_CSS_URL
    }

    fun clearSavedCss(context: Context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .remove(CSS_URL)
            .putBoolean(DEFAULT_CSS_DISABLED, true)
            .apply()
    }

    fun load(context: Context, rawUrl: String): Result<LoadedRemoteFontSet> = runCatching {
        val cssUri = URI(rawUrl.trim())
        require(cssUri.scheme.equals("https", true) || cssUri.scheme.equals("http", true)) {
            "La hoja de estilos debe usar HTTP o HTTPS"
        }
        require(!cssUri.host.isNullOrBlank()) { "La URL de la hoja CSS no tiene host" }
        val css = readBytes(cssUri.toURL(), MAX_CSS_BYTES).toString(Charsets.UTF_8)
        val faces = parseLoadableFaces(css, cssUri)
        if (faces.isEmpty()) throw IOException("La hoja CSS no contiene fuentes TTF u OTF")

        val directory = File(context.filesDir, "mpv/fonts").apply { mkdirs() }
        val downloaded = linkedMapOf<URI, File>()
        var totalBytes = 0L
        faces.forEach { face ->
            if (face.url in downloaded) return@forEach
            val extension = face.url.path.substringAfterLast('.', "ttf")
                .lowercase()
                .takeIf { it in setOf("ttf", "otf") }
                ?: "ttf"
            val file = File(directory, "${sha256(face.url.toString())}.$extension")
            if (!file.isFile || file.length() <= 0L || file.length() > MAX_FONT_BYTES) {
                val bytes = readBytes(face.url.toURL(), MAX_FONT_BYTES)
                totalBytes += bytes.size
                if (totalBytes > MAX_TOTAL_FONT_BYTES) {
                    throw IOException("La hoja CSS contiene demasiados datos de fuentes")
                }
                file.outputStream().buffered().use { it.write(bytes) }
            } else {
                totalBytes += file.length()
            }
            downloaded[face.url] = file
        }

        val families = faces.groupBy(FontFace::family)
        families.forEach { (family, familyFaces) ->
            val preferred = familyFaces.minBy { face ->
                abs(face.weight - 400) + if (face.italic) 1_000 else 0
            }
            val typeface = Typeface.createFromFile(downloaded.getValue(preferred.url))
            SubtitleFontRegistry.register(family, typeface)
        }
        val familyNames = families.keys.toList()
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(CSS_URL, cssUri.toString())
            .putBoolean(DEFAULT_CSS_DISABLED, false)
            .apply()
        LoadedRemoteFontSet(familyNames, downloaded.values.toList())
    }

    private data class FontFace(
        val family: String,
        val url: URI,
        val weight: Int,
        val italic: Boolean,
    )

    private fun parseLoadableFaces(css: String, base: URI): List<FontFace> {
        val blockRegex = Regex("@font-face\\s*\\{(.*?)\\}", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val familyRegex = Regex("font-family\\s*:\\s*['\"]?([^;'\"}]+)", RegexOption.IGNORE_CASE)
        val urlRegex = Regex("url\\(\\s*['\"]?([^)\'\"]+)['\"]?\\s*\\)", RegexOption.IGNORE_CASE)
        val weightRegex = Regex("font-weight\\s*:\\s*([^;}]+)", RegexOption.IGNORE_CASE)
        val styleRegex = Regex("font-style\\s*:\\s*([^;}]+)", RegexOption.IGNORE_CASE)
        return blockRegex.findAll(css).mapNotNull { match ->
            val block = match.groupValues[1]
            val family = familyRegex.find(block)?.groupValues?.get(1)
                ?.trim()
                ?.takeIf { it.isNotEmpty() && it.length <= 96 }
                ?: return@mapNotNull null
            val chosen = urlRegex.findAll(block)
                .map { it.groupValues[1].trim() }
                .firstOrNull { candidate ->
                    candidate.substringBefore('?').substringAfterLast('.', "").lowercase() in setOf("ttf", "otf")
                }
                ?: return@mapNotNull null
            val resolved = runCatching { base.resolve(chosen) }.getOrNull() ?: return@mapNotNull null
            if (!resolved.scheme.equals("http", true) && !resolved.scheme.equals("https", true)) {
                return@mapNotNull null
            }
            val weightValue = weightRegex.find(block)?.groupValues?.get(1)?.trim()?.lowercase()
            val weight = when (weightValue) {
                "normal", null -> 400
                "bold" -> 700
                else -> weightValue.toIntOrNull()?.coerceIn(1, 1_000) ?: 400
            }
            val italic = styleRegex.find(block)?.groupValues?.get(1)
                ?.contains("italic", ignoreCase = true) == true
            FontFace(family, resolved, weight, italic)
        }.take(MAX_FONT_FACES).toList()
    }

    private fun readBytes(url: URL, maximum: Int): ByteArray {
        val connection = (url.openConnection() as? HttpURLConnection)
            ?: throw IOException("Protocolo de fuente no compatible")
        connection.connectTimeout = 10_000
        connection.readTimeout = 20_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "Syncplay-Droid/0.4")
        return try {
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("El servidor respondió HTTP $status")
            // URLConnection#getContentLengthLong requires API 24; font files are capped far below
            // Int.MAX_VALUE, so the API-1 integer accessor is sufficient on our API 23 floor.
            val declared = connection.contentLength.toLong()
            if (declared > maximum) throw IOException("El archivo remoto es demasiado grande")
            connection.inputStream.use { input ->
                val initial = declared.takeIf { it in 1..maximum.toLong() }?.toInt() ?: 16_384
                val output = java.io.ByteArrayOutputStream(initial)
                val buffer = ByteArray(16_384)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maximum) throw IOException("El archivo remoto es demasiado grande")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
