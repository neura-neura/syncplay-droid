package dev.neura.syncplay.data

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.neura.syncplay.ui.subtitle.SubtitleAppearance
import dev.neura.syncplay.ui.subtitle.SubtitlePreferences
import dev.neura.syncplay.ui.subtitle.SubtitleSyncSettings
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val Context.subtitlePreferencesDataStore by preferencesDataStore(
    name = "subtitle_preferences",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Stable, schema-version-free codec for the subtitle settings file.
 *
 * The map API is intentionally pure so malformed persisted values can be tested without an
 * Android Context. DataStore uses the same keys below and normalizes all values on read and write.
 */
object SubtitlePreferencesCodec {
    const val FONT_SIZE_KEY = "appearance_font_size"
    const val TEXT_COLOR_KEY = "appearance_text_color"
    const val BACKGROUND_COLOR_KEY = "appearance_background_color"
    const val BACKGROUND_OPACITY_KEY = "appearance_background_opacity"
    const val BOTTOM_OFFSET_KEY = "appearance_bottom_offset"
    const val FONT_WEIGHT_KEY = "appearance_font_weight"
    const val FONT_FAMILY_KEY = "appearance_font_family"
    const val CUSTOM_MAX_WIDTH_KEY = "appearance_use_custom_max_width"
    const val MAX_WIDTH_KEY = "appearance_max_width"
    const val PADDING_X_KEY = "appearance_padding_x"
    const val PADDING_Y_KEY = "appearance_padding_y"
    const val BORDER_RADIUS_KEY = "appearance_border_radius"
    const val LINE_HEIGHT_KEY = "appearance_line_height"
    const val LETTER_SPACING_KEY = "appearance_letter_spacing"
    const val TEXT_SHADOW_KEY = "appearance_text_shadow"
    const val OFFSET_MS_KEY = "sync_offset_ms"
    const val REMEMBER_OFFSET_KEY = "sync_remember_offset"

    /** Encode normalized values into a primitive map suitable for preferences or JSON fixtures. */
    fun encode(value: SubtitlePreferences): Map<String, Any> {
        val normalized = value.normalized()
        val style = normalized.appearance
        val sync = normalized.sync
        return linkedMapOf(
            FONT_SIZE_KEY to style.fontSize,
            TEXT_COLOR_KEY to style.textColor,
            BACKGROUND_COLOR_KEY to style.backgroundColor,
            BACKGROUND_OPACITY_KEY to style.backgroundOpacity,
            BOTTOM_OFFSET_KEY to style.bottomOffset,
            FONT_WEIGHT_KEY to style.fontWeight,
            FONT_FAMILY_KEY to style.fontFamily,
            CUSTOM_MAX_WIDTH_KEY to style.useCustomMaxWidth,
            MAX_WIDTH_KEY to style.maxWidth,
            PADDING_X_KEY to style.paddingX,
            PADDING_Y_KEY to style.paddingY,
            BORDER_RADIUS_KEY to style.borderRadius,
            LINE_HEIGHT_KEY to style.lineHeight,
            LETTER_SPACING_KEY to style.letterSpacing,
            TEXT_SHADOW_KEY to style.textShadow,
            OFFSET_MS_KEY to sync.offsetMs,
            REMEMBER_OFFSET_KEY to sync.rememberOffset,
        )
    }

    /** Decode a primitive map, using exact defaults for absent or malformed fields. */
    fun decode(values: Map<String, *>): SubtitlePreferences {
        val defaults = SubtitlePreferences.DEFAULT
        val defaultStyle = defaults.appearance
        val defaultSync = defaults.sync
        val style = SubtitleAppearance(
            fontSize = values.floatValue(FONT_SIZE_KEY, defaultStyle.fontSize),
            textColor = values.stringValue(TEXT_COLOR_KEY, defaultStyle.textColor),
            backgroundColor = values.stringValue(BACKGROUND_COLOR_KEY, defaultStyle.backgroundColor),
            backgroundOpacity = values.floatValue(BACKGROUND_OPACITY_KEY, defaultStyle.backgroundOpacity),
            bottomOffset = values.floatValue(BOTTOM_OFFSET_KEY, defaultStyle.bottomOffset),
            fontWeight = values.intValue(FONT_WEIGHT_KEY, defaultStyle.fontWeight),
            fontFamily = values.stringValue(FONT_FAMILY_KEY, defaultStyle.fontFamily),
            useCustomMaxWidth = values.booleanValue(CUSTOM_MAX_WIDTH_KEY, defaultStyle.useCustomMaxWidth),
            maxWidth = values.floatValue(MAX_WIDTH_KEY, defaultStyle.maxWidth),
            paddingX = values.floatValue(PADDING_X_KEY, defaultStyle.paddingX),
            paddingY = values.floatValue(PADDING_Y_KEY, defaultStyle.paddingY),
            borderRadius = values.floatValue(BORDER_RADIUS_KEY, defaultStyle.borderRadius),
            lineHeight = values.floatValue(LINE_HEIGHT_KEY, defaultStyle.lineHeight),
            letterSpacing = values.floatValue(LETTER_SPACING_KEY, defaultStyle.letterSpacing),
            textShadow = values.booleanValue(TEXT_SHADOW_KEY, defaultStyle.textShadow),
        ).normalized()
        val sync = SubtitleSyncSettings(
            offsetMs = values.longValue(OFFSET_MS_KEY, defaultSync.offsetMs),
            rememberOffset = values.booleanValue(REMEMBER_OFFSET_KEY, defaultSync.rememberOffset),
        ).normalized()
        return SubtitlePreferences(style, sync)
    }

    internal fun read(preferences: Preferences): SubtitlePreferences = decode(
        mapOf(
            FONT_SIZE_KEY to preferences[FONT_SIZE],
            TEXT_COLOR_KEY to preferences[TEXT_COLOR],
            BACKGROUND_COLOR_KEY to preferences[BACKGROUND_COLOR],
            BACKGROUND_OPACITY_KEY to preferences[BACKGROUND_OPACITY],
            BOTTOM_OFFSET_KEY to preferences[BOTTOM_OFFSET],
            FONT_WEIGHT_KEY to preferences[FONT_WEIGHT],
            FONT_FAMILY_KEY to preferences[FONT_FAMILY],
            CUSTOM_MAX_WIDTH_KEY to preferences[CUSTOM_MAX_WIDTH],
            MAX_WIDTH_KEY to preferences[MAX_WIDTH],
            PADDING_X_KEY to preferences[PADDING_X],
            PADDING_Y_KEY to preferences[PADDING_Y],
            BORDER_RADIUS_KEY to preferences[BORDER_RADIUS],
            LINE_HEIGHT_KEY to preferences[LINE_HEIGHT],
            LETTER_SPACING_KEY to preferences[LETTER_SPACING],
            TEXT_SHADOW_KEY to preferences[TEXT_SHADOW],
            OFFSET_MS_KEY to preferences[OFFSET_MS],
            REMEMBER_OFFSET_KEY to preferences[REMEMBER_OFFSET],
        ),
    )

    internal fun write(preferences: MutablePreferences, value: SubtitlePreferences) {
        val normalized = value.normalized()
        val style = normalized.appearance
        val sync = normalized.sync
        preferences[FONT_SIZE] = style.fontSize
        preferences[TEXT_COLOR] = style.textColor
        preferences[BACKGROUND_COLOR] = style.backgroundColor
        preferences[BACKGROUND_OPACITY] = style.backgroundOpacity
        preferences[BOTTOM_OFFSET] = style.bottomOffset
        preferences[FONT_WEIGHT] = style.fontWeight
        preferences[FONT_FAMILY] = style.fontFamily
        preferences[CUSTOM_MAX_WIDTH] = style.useCustomMaxWidth
        preferences[MAX_WIDTH] = style.maxWidth
        preferences[PADDING_X] = style.paddingX
        preferences[PADDING_Y] = style.paddingY
        preferences[BORDER_RADIUS] = style.borderRadius
        preferences[LINE_HEIGHT] = style.lineHeight
        preferences[LETTER_SPACING] = style.letterSpacing
        preferences[TEXT_SHADOW] = style.textShadow
        preferences[OFFSET_MS] = sync.offsetMs
        preferences[REMEMBER_OFFSET] = sync.rememberOffset
    }

    private val FONT_SIZE = floatPreferencesKey(FONT_SIZE_KEY)
    private val TEXT_COLOR = stringPreferencesKey(TEXT_COLOR_KEY)
    private val BACKGROUND_COLOR = stringPreferencesKey(BACKGROUND_COLOR_KEY)
    private val BACKGROUND_OPACITY = floatPreferencesKey(BACKGROUND_OPACITY_KEY)
    private val BOTTOM_OFFSET = floatPreferencesKey(BOTTOM_OFFSET_KEY)
    private val FONT_WEIGHT = intPreferencesKey(FONT_WEIGHT_KEY)
    private val FONT_FAMILY = stringPreferencesKey(FONT_FAMILY_KEY)
    private val CUSTOM_MAX_WIDTH = booleanPreferencesKey(CUSTOM_MAX_WIDTH_KEY)
    private val MAX_WIDTH = floatPreferencesKey(MAX_WIDTH_KEY)
    private val PADDING_X = floatPreferencesKey(PADDING_X_KEY)
    private val PADDING_Y = floatPreferencesKey(PADDING_Y_KEY)
    private val BORDER_RADIUS = floatPreferencesKey(BORDER_RADIUS_KEY)
    private val LINE_HEIGHT = floatPreferencesKey(LINE_HEIGHT_KEY)
    private val LETTER_SPACING = floatPreferencesKey(LETTER_SPACING_KEY)
    private val TEXT_SHADOW = booleanPreferencesKey(TEXT_SHADOW_KEY)
    private val OFFSET_MS = longPreferencesKey(OFFSET_MS_KEY)
    private val REMEMBER_OFFSET = booleanPreferencesKey(REMEMBER_OFFSET_KEY)
}

/** Process-death-safe repository for subtitle appearance and synchronization settings. */
class SubtitlePreferencesRepository(context: Context) {
    private val dataStore = context.applicationContext.subtitlePreferencesDataStore
    private val saveMutex = Mutex()

    /** Emits defaults until the file is available and recovers transient read I/O failures. */
    val preferences: Flow<SubtitlePreferences> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map(SubtitlePreferencesCodec::read)

    suspend fun save(value: SubtitlePreferences) = saveMutex.withLock {
        dataStore.edit { target -> SubtitlePreferencesCodec.write(target, value) }
    }

    suspend fun update(transform: (SubtitlePreferences) -> SubtitlePreferences) = saveMutex.withLock {
        dataStore.edit { target ->
            val current = SubtitlePreferencesCodec.read(target)
            SubtitlePreferencesCodec.write(target, transform(current))
        }
    }

    suspend fun saveAppearance(value: SubtitleAppearance) = update { it.copy(appearance = value) }

    suspend fun updateAppearance(transform: (SubtitleAppearance) -> SubtitleAppearance) =
        update { current -> current.copy(appearance = transform(current.appearance)) }

    suspend fun saveSync(value: SubtitleSyncSettings) = update { it.copy(sync = value) }

    suspend fun updateSync(transform: (SubtitleSyncSettings) -> SubtitleSyncSettings) =
        update { current -> current.copy(sync = transform(current.sync)) }
}

private fun Map<String, *>.floatValue(key: String, fallback: Float): Float =
    numberValue(key)?.toFloat()?.takeIf(Float::isFinite) ?: fallback

private fun Map<String, *>.intValue(key: String, fallback: Int): Int =
    numberValue(key)?.toInt() ?: fallback

private fun Map<String, *>.longValue(key: String, fallback: Long): Long =
    numberValue(key)?.toLong() ?: fallback

private fun Map<String, *>.numberValue(key: String): Number? = when (val value = this[key]) {
    is Number -> value
    is String -> value.trim().toDoubleOrNull()?.takeIf(Double::isFinite)
    else -> null
}

private fun Map<String, *>.stringValue(key: String, fallback: String): String =
    (this[key] as? String)?.takeIf { it.isNotBlank() } ?: fallback

private fun Map<String, *>.booleanValue(key: String, fallback: Boolean): Boolean = when (val value = this[key]) {
    is Boolean -> value
    is String -> value.trim().lowercase().toBooleanStrictOrNull() ?: fallback
    is Number -> value.toInt() != 0
    else -> fallback
}
