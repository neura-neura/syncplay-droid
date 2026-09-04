package dev.neura.syncplay.ui

import android.content.Context
import android.graphics.Typeface
import dev.neura.syncplay.ui.subtitle.SubtitleFontRegistry
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

data class LoadedRemoteFontFace(
    val family: String,
    val typeface: Typeface,
    val weight: Int,
    val italic: Boolean,
)

data class LoadedRemoteFontSet(
    val familyNames: List<String>,
    val files: List<File>,
    private val faces: List<LoadedRemoteFontFace> = emptyList(),
) {
    val primaryFamilyName: String get() = familyNames.first()

    /** Register faces only after the caller accepts this load as the current request. */
    fun register() {
        familyNames.forEach(SubtitleFontRegistry::unregister)
        faces.forEach { face ->
            SubtitleFontRegistry.register(face.family, face.typeface, face.weight, face.italic)
        }
    }

    fun availableWeights(family: String, italic: Boolean = false): Set<Int> = faces
        .filter { it.family.equals(family, ignoreCase = true) && it.italic == italic }
        .mapTo(linkedSetOf(), LoadedRemoteFontFace::weight)
}

/** Loads the TTF/OTF faces and families declared by a Noir-compatible @font-face stylesheet. */
object RemoteSubtitleFontLoader {
    /** Noir Player's GothamPro stylesheet; downloaded once and then restored from app cache. */
    const val DEFAULT_CSS_URL =
        "https://cdn.jsdelivr.net/npm/gotham-pro-font@1.0.0/fonts.min.css"
    private const val PREFERENCES = "subtitle_remote_font"
    private const val CSS_URL = "css_url"
    private const val MAX_CSS_BYTES = 1_048_576
    private const val MAX_FONT_BYTES = 25 * 1_048_576
    private const val MAX_TOTAL_FONT_BYTES = 80 * 1_048_576
    private const val MAX_FONT_FACES = 32

    fun savedCssUrl(context: Context): String {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        return preferences.getString(CSS_URL, DEFAULT_CSS_URL).orEmpty()
    }

    fun clearSavedCss(context: Context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .remove(CSS_URL)
            .apply()
    }

    /** Persist a URL only after its load has been accepted by the caller. */
    fun saveCssUrl(context: Context, rawUrl: String) {
        val cssUri = parseCssUri(rawUrl)
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(CSS_URL, cssUri.toString())
            .apply()
    }

    /** Load from the network, falling back to the last complete app-private cache on failure. */
    fun load(context: Context, rawUrl: String): Result<LoadedRemoteFontSet> =
        loadInternal(context, rawUrl, allowNetwork = true)

    /** Restore a previously downloaded stylesheet and all of its faces without network access. */
    fun loadCached(context: Context, rawUrl: String): Result<LoadedRemoteFontSet> =
        loadInternal(context, rawUrl, allowNetwork = false)

    private fun loadInternal(
        context: Context,
        rawUrl: String,
        allowNetwork: Boolean,
    ): Result<LoadedRemoteFontSet> {
        if (!allowNetwork) return loadOnce(context, rawUrl, allowNetwork = false)
        val networkResult = loadOnce(context, rawUrl, allowNetwork = true)
        if (networkResult.isSuccess) return networkResult
        val cachedResult = loadOnce(context, rawUrl, allowNetwork = false)
        return if (cachedResult.isSuccess) cachedResult else networkResult
    }

    private fun loadOnce(
        context: Context,
        rawUrl: String,
        allowNetwork: Boolean,
    ): Result<LoadedRemoteFontSet> = runCatching {
        val cssUri = parseCssUri(rawUrl)
        val directory = File(context.filesDir, "mpv/fonts").apply { mkdirs() }
        val cssCache = File(directory, "css-${sha256(cssUri.toString())}.css")
        var cssCameFromNetwork = false
        val cssBytes = if (allowNetwork) {
            readBytes(cssUri.toURL(), MAX_CSS_BYTES).also { cssCameFromNetwork = true }
        } else {
            readCachedBytes(cssCache, MAX_CSS_BYTES).getOrThrow()
        }
        val css = cssBytes.toString(Charsets.UTF_8)
        val faces = parseLoadableFaces(css, cssUri)
        if (faces.isEmpty()) throw IOException("La hoja CSS no contiene fuentes TTF u OTF")

        val downloaded = linkedMapOf<URI, File>()
        val validatedTypefaces = linkedMapOf<URI, Typeface?>()
        val accountedSources = hashSetOf<URI>()
        var totalBytes = 0L
        val resolvedFaces = faces.mapNotNull { face ->
            // Browsers prefer the first format they understand, but this stylesheet lists CFF OTF
            // before TTF.  API 23 devices are much more reliable with the TrueType face, so try
            // TTF first and fall back to OTF only when the TTF is unavailable or invalid.
            face.sources.firstNotNullOfOrNull { source ->
                val loaded = runCatching {
                    val extension = sourceFontExtension(source)
                    val destination = File(directory, "${sha256(source.toString())}.$extension")
                    var typeface = destination
                        .takeIf { it.isFile && it.length() in 1..MAX_FONT_BYTES.toLong() }
                        ?.let { cached ->
                            validatedTypefaces.getOrPut(source) { validTypeface(cached) }
                        }
                    if (typeface == null) {
                        if (!allowNetwork) throw IOException("La fuente no está completa en caché")
                        val bytes = readBytes(source.toURL(), MAX_FONT_BYTES)
                        if (totalBytes + bytes.size > MAX_TOTAL_FONT_BYTES) {
                            throw FontBudgetExceededException()
                        }
                        typeface = installTypefaceAtomically(destination, bytes)
                        validatedTypefaces[source] = typeface
                    }
                    if (accountedSources.add(source)) {
                        totalBytes += destination.length()
                        if (totalBytes > MAX_TOTAL_FONT_BYTES) throw FontBudgetExceededException()
                    }
                    downloaded[source] = destination
                    destination to checkNotNull(typeface)
                }.getOrElse { error ->
                    if (error is FontBudgetExceededException) throw error
                    return@firstNotNullOfOrNull null
                }
                ResolvedFontFace(face, loaded.second)
            }
        }
        if (resolvedFaces.isEmpty()) {
            throw IOException("La hoja CSS no contiene una fuente compatible con Android")
        }

        val families = resolvedFaces.groupBy(ResolvedFontFace::family)
        val familyNames = families.keys.toList()
        val loadedSet = LoadedRemoteFontSet(
            familyNames = familyNames,
            files = downloaded.values.toList(),
            faces = resolvedFaces.map { face ->
                LoadedRemoteFontFace(face.family, face.typeface, face.weight, face.italic)
            },
        )
        // Do not replace a known-good stylesheet until every referenced Android face has been
        // downloaded and parsed.  A broken network response therefore cannot poison offline
        // startup on the next app launch.
        if (cssCameFromNetwork) writeAtomically(cssCache, cssBytes)
        loadedSet
    }

    private fun parseCssUri(rawUrl: String): URI {
        val cssUri = URI(rawUrl.trim())
        require(cssUri.scheme.equals("https", true) || cssUri.scheme.equals("http", true)) {
            "La hoja de estilos debe usar HTTP o HTTPS"
        }
        require(!cssUri.host.isNullOrBlank()) { "La URL de la hoja CSS no tiene host" }
        return cssUri
    }

    private data class FontFace(
        val family: String,
        val sources: List<URI>,
        val weight: Int,
        val italic: Boolean,
    )

    private data class ResolvedFontFace(
        val family: String,
        val typeface: Typeface,
        val weight: Int,
        val italic: Boolean,
    ) {
        constructor(face: FontFace, typeface: Typeface) : this(
            family = face.family,
            typeface = typeface,
            weight = face.weight,
            italic = face.italic,
        )
    }

    private class FontBudgetExceededException : IOException("La hoja CSS contiene demasiados datos de fuentes")

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
            val sources = urlRegex.findAll(block)
                .map { it.groupValues[1].trim() }
                .filter { it.isNotEmpty() }
                .filter { sourceFormatRank(it) < Int.MAX_VALUE }
                .distinct()
                .sortedBy(::sourceFormatRank)
                .mapNotNull { candidate ->
                    val resolved = runCatching { base.resolve(candidate) }.getOrNull()
                    resolved?.takeIf {
                        it.scheme.equals("http", true) || it.scheme.equals("https", true)
                    }
                }
                .toList()
                .takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val weightValue = weightRegex.find(block)?.groupValues?.get(1)?.trim()?.lowercase()
            val weight = when (weightValue) {
                "normal", null -> 400
                "bold" -> 700
                else -> weightValue.toIntOrNull()?.coerceIn(1, 1_000) ?: 400
            }
            val italic = styleRegex.find(block)?.groupValues?.get(1)
                ?.contains("italic", ignoreCase = true) == true
            FontFace(family, sources, weight, italic)
        }.take(MAX_FONT_FACES).toList()
    }

    /** TTF is the most compatible Android face; OTF is a deliberate fallback for modern devices. */
    internal fun sourceFormatRank(value: String): Int = when (sourceFontExtension(value)) {
        "ttf" -> 0
        "otf" -> 1
        else -> Int.MAX_VALUE
    }

    private fun sourceFontExtension(value: URI): String = sourceFontExtension(value.toString())

    private fun sourceFontExtension(value: String): String = value
        .substringBefore('?')
        .substringBefore('#')
        .substringAfterLast('.', "")
        .lowercase(Locale.ROOT)

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

    private fun readCachedBytes(file: File, maximum: Int): Result<ByteArray> = runCatching {
        if (!file.isFile || file.length() !in 1..maximum.toLong()) {
            throw IOException("La fuente no está completa en caché")
        }
        file.readBytes().also { bytes ->
            if (bytes.isEmpty() || bytes.size > maximum) {
                throw IOException("La fuente no está completa en caché")
            }
        }
    }

    private fun validTypeface(file: File): Typeface? = runCatching {
        Typeface.createFromFile(file)
    }.getOrNull()?.takeUnless { it == Typeface.DEFAULT }

    /** Validate a staged font before replacing the last known-good cached face. */
    private fun installTypefaceAtomically(destination: File, bytes: ByteArray): Typeface {
        val staged = writeStagedFile(destination, bytes)
        try {
            validTypeface(staged)
                ?: throw IOException("Android no reconoce la fuente descargada")
            replaceWithStagedFile(staged, destination)
            return validTypeface(destination)
                ?: throw IOException("Android no reconoce la fuente guardada")
        } finally {
            if (staged.exists()) staged.delete()
        }
    }

    private fun writeAtomically(destination: File, bytes: ByteArray) {
        val staged = writeStagedFile(destination, bytes)
        try {
            replaceWithStagedFile(staged, destination)
        } finally {
            if (staged.exists()) staged.delete()
        }
    }

    private fun writeStagedFile(destination: File, bytes: ByteArray): File {
        destination.parentFile?.mkdirs()
        val staged = File(
            destination.parentFile,
            ".${destination.name}.${UUID.randomUUID()}.tmp",
        )
        try {
            FileOutputStream(staged).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            return staged
        } catch (error: Exception) {
            staged.delete()
            throw error
        }
    }

    /**
     * File.renameTo is atomic on Android's app-private filesystem.  The backup keeps the previous
     * cache recoverable if a device/filesystem refuses the final rename.
     */
    private fun replaceWithStagedFile(staged: File, destination: File) {
        val backup = File(
            destination.parentFile,
            ".${destination.name}.${UUID.randomUUID()}.bak",
        )
        val hadDestination = destination.exists()
        if (hadDestination && !destination.renameTo(backup)) {
            throw IOException("No se pudo preparar la caché de fuentes")
        }
        if (staged.renameTo(destination)) {
            if (backup.exists()) backup.delete()
            return
        }
        if (backup.exists()) backup.renameTo(destination)
        throw IOException("No se pudo guardar la caché de fuentes")
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
