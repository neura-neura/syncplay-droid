package dev.neura.syncplay.ui.subtitle

import android.graphics.Typeface
import android.util.Xml
import java.io.File

/** Discovers named Android font families without relying on hidden framework APIs. */
object SystemSubtitleFontCatalog {
    private val genericFamilies = listOf(
        SubtitleAppearance.DEFAULT_FONT_FAMILY,
        "sans-serif",
        "sans-serif-condensed",
        "sans-serif-medium",
        "serif",
        "monospace",
        "cursive",
    )

    fun load(): List<String> {
        val names = linkedSetOf<String>().apply { addAll(genericFamilies) }
        listOf(
            File("/system/etc/fonts.xml"),
            File("/product/etc/fonts_customization.xml"),
        ).filter(File::isFile).forEach { file ->
            runCatching { readFamilyNames(file) }.getOrDefault(emptyList()).forEach(names::add)
        }
        names.take(MAX_FAMILIES).forEach { family ->
            val platformName = if (family == SubtitleAppearance.DEFAULT_FONT_FAMILY) {
                "sans-serif"
            } else {
                family
            }
            runCatching { Typeface.create(platformName, Typeface.NORMAL) }
                .getOrNull()
                ?.let { SubtitleFontRegistry.register(family, it) }
        }
        return names.take(MAX_FAMILIES)
    }

    private fun readFamilyNames(file: File): List<String> {
        val names = linkedSetOf<String>()
        file.inputStream().buffered().use { input ->
            val parser = Xml.newPullParser().apply { setInput(input, null) }
            var event = parser.eventType
            while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (event == org.xmlpull.v1.XmlPullParser.START_TAG &&
                    (parser.name == "family" || parser.name == "alias")
                ) {
                    parser.getAttributeValue(null, "name")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() && it.length <= MAX_FAMILY_NAME_LENGTH }
                        ?.let(names::add)
                }
                event = parser.next()
            }
        }
        return names.toList()
    }

    private const val MAX_FAMILIES = 160
    private const val MAX_FAMILY_NAME_LENGTH = 96
}
