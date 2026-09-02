package dev.neura.syncplay.ui

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import java.util.Locale

/** Prefix propagated from a side-loaded subtitle configuration into Media3's merged Format id. */
internal const val EXTERNAL_SUBTITLE_ID_PREFIX = "syncplay-external-subtitle:"

/** Runtime reference for one currently available Media3 text track. */
internal data class SubtitleTrackReference(
    val ui: SubtitleTrackUi,
    val mediaTrackGroup: TrackGroup,
    val trackIndex: Int,
    val formatId: String?,
)

/**
 * Flatten Media3's text groups into stable options for the current prepared item.
 *
 * Track group/index ids deliberately describe only the current [Tracks] snapshot. The UI never
 * persists them across media items, and selection resolves the id against a fresh snapshot before
 * installing an override.
 */
internal fun subtitleTrackReferences(tracks: Tracks): List<SubtitleTrackReference> {
    val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
    var visibleIndex = 0
    return buildList {
        textGroups.forEachIndexed { groupIndex, group ->
            repeat(group.length) { trackIndex ->
                val format = group.getTrackFormat(trackIndex)
                val external = format.id.isExternalSubtitleFormatId()
                val language = format.language.displayLanguageInSpanish()
                val formatName = format.subtitleFormatName()
                val fallbackNumber = ++visibleIndex
                val label = format.label
                    ?.takeIf { it.isNotBlank() }
                    ?: language
                    ?: "Pista $fallbackNumber"
                val detail = listOfNotNull(
                    language?.takeUnless { it.equals(label, ignoreCase = true) },
                    formatName,
                ).distinct().joinToString(" · ").takeIf { it.isNotBlank() }

                add(
                    SubtitleTrackReference(
                        ui = SubtitleTrackUi(
                            id = "subtitle:$groupIndex:$trackIndex",
                            label = label,
                            detail = detail,
                            isSelected = group.isTrackSelected(trackIndex),
                            isSupported = group.isTrackSupported(trackIndex),
                            isExternal = external,
                        ),
                        mediaTrackGroup = group.mediaTrackGroup,
                        trackIndex = trackIndex,
                        formatId = format.id,
                    ),
                )
            }
        }
    }
}

/** Find the exact side-loaded track after MergingMediaSource has prefixed its Format id. */
internal fun findExternalSubtitleTrack(
    tracks: Tracks,
    requestedFormatId: String,
): SubtitleTrackReference? = subtitleTrackReferences(tracks).firstOrNull { reference ->
    reference.formatId.matchesMergedFormatId(requestedFormatId)
}

/** Replace all prior text choices with the exact track represented by [reference]. */
internal fun TrackSelectionParameters.selectSubtitle(
    reference: SubtitleTrackReference,
): TrackSelectionParameters = buildUpon()
    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
    .setOverrideForType(
        TrackSelectionOverride(reference.mediaTrackGroup, listOf(reference.trackIndex)),
    )
    .build()

/** Clear stale overrides and either enable automatic text selection or disable text completely. */
internal fun TrackSelectionParameters.resetSubtitleSelection(
    disabled: Boolean,
): TrackSelectionParameters = buildUpon()
    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, disabled)
    .build()

private fun String?.isExternalSubtitleFormatId(): Boolean =
    this?.let {
        it.startsWith(EXTERNAL_SUBTITLE_ID_PREFIX) || it.contains(":$EXTERNAL_SUBTITLE_ID_PREFIX")
    } == true

private fun String?.matchesMergedFormatId(requestedFormatId: String): Boolean =
    this == requestedFormatId || this?.endsWith(":$requestedFormatId") == true

private fun String?.displayLanguageInSpanish(): String? {
    val code = this?.trim()?.takeIf { it.isNotEmpty() && !it.equals("und", ignoreCase = true) }
        ?: return null
    val displayName = runCatching {
        Locale.forLanguageTag(code.replace('_', '-')).getDisplayLanguage(SPANISH_LOCALE)
    }.getOrNull()?.takeIf { it.isNotBlank() && !it.equals(code, ignoreCase = true) }
        ?: code
    return displayName.replaceFirstChar { first ->
        if (first.isLowerCase()) first.titlecase(SPANISH_LOCALE) else first.toString()
    }
}

private fun Format.subtitleFormatName(): String? = when (sampleMimeType) {
    MimeTypes.APPLICATION_PGS -> "PGS"
    MimeTypes.TEXT_SSA -> "ASS/SSA"
    MimeTypes.APPLICATION_SUBRIP -> "SRT"
    MimeTypes.TEXT_VTT, MimeTypes.APPLICATION_MP4VTT -> "WebVTT"
    MimeTypes.APPLICATION_TTML -> "TTML"
    MimeTypes.APPLICATION_DVBSUBS -> "DVB"
    else -> sampleMimeType?.substringAfterLast('/')?.uppercase(Locale.ROOT)
}

private val SPANISH_LOCALE = Locale.forLanguageTag("es")
