package dev.neura.syncplay.ui

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SubtitleTrackSelectionTest {
    @Test
    fun externalAssWinsOverDefaultEmbeddedPgsByExactMergedId() {
        val embedded = textGroup(
            id = "0:3",
            label = null,
            language = "jpn",
            mimeType = MimeTypes.APPLICATION_PGS,
            selected = true,
        )
        val requestedId = "${EXTERNAL_SUBTITLE_ID_PREFIX}42"
        val external = textGroup(
            id = "1:$requestedId",
            label = "dialogue.es.ass",
            language = null,
            mimeType = MimeTypes.TEXT_SSA,
            selected = false,
        )
        val tracks = Tracks(listOf(embedded, external))

        val reference = findExternalSubtitleTrack(tracks, requestedId)

        assertNotNull(reference)
        assertEquals("dialogue.es.ass", reference?.ui?.label)
        assertTrue(reference?.ui?.isExternal == true)
        assertEquals(0, reference?.trackIndex)

        val parameters = TrackSelectionParameters.Builder().build().selectSubtitle(reference!!)
        assertFalse(C.TRACK_TYPE_TEXT in parameters.disabledTrackTypes)
        assertEquals(1, parameters.overrides.size)
        assertEquals(listOf(0), parameters.overrides.values.single().trackIndices)
        assertEquals(external.mediaTrackGroup, parameters.overrides.keys.single())
    }

    @Test
    fun replacingExternalTrackClearsTheOldOverride() {
        val first = textGroup(
            id = "1:${EXTERNAL_SUBTITLE_ID_PREFIX}1",
            label = "first.srt",
            language = "spa",
            mimeType = MimeTypes.APPLICATION_SUBRIP,
            selected = true,
        )
        val second = textGroup(
            id = "1:${EXTERNAL_SUBTITLE_ID_PREFIX}2",
            label = "second.ass",
            language = "spa",
            mimeType = MimeTypes.TEXT_SSA,
            selected = false,
        )
        val firstReference = subtitleTrackReferences(Tracks(listOf(first))).single()
        val secondReference = subtitleTrackReferences(Tracks(listOf(second))).single()

        val parameters = TrackSelectionParameters.Builder().build()
            .selectSubtitle(firstReference)
            .selectSubtitle(secondReference)

        assertEquals(1, parameters.overrides.size)
        assertEquals(second.mediaTrackGroup, parameters.overrides.keys.single())
    }

    @Test
    fun disablingSubtitlesClearsOverridesAndDisablesText() {
        val group = textGroup(
            id = "0:3",
            label = "Japanese",
            language = "jpn",
            mimeType = MimeTypes.APPLICATION_PGS,
            selected = true,
        )
        val selected = TrackSelectionParameters.Builder().build()
            .selectSubtitle(subtitleTrackReferences(Tracks(listOf(group))).single())

        val disabled = selected.resetSubtitleSelection(disabled = true)

        assertTrue(disabled.overrides.isEmpty())
        assertTrue(C.TRACK_TYPE_TEXT in disabled.disabledTrackTypes)
    }

    @Test
    fun embeddedTracksExposeLanguageFormatAndSupport() {
        val tracks = Tracks(
            listOf(
                textGroup(
                    id = "0:4",
                    label = null,
                    language = "eng",
                    mimeType = MimeTypes.APPLICATION_PGS,
                    selected = false,
                ),
            ),
        )

        val option = subtitleTrackReferences(tracks).single().ui

        assertEquals("Inglés", option.label)
        assertEquals("PGS", option.detail)
        assertTrue(option.isSupported)
        assertFalse(option.isExternal)
    }

    private fun textGroup(
        id: String,
        label: String?,
        language: String?,
        mimeType: String,
        selected: Boolean,
    ): Tracks.Group {
        val format = Format.Builder()
            .setId(id)
            .setLabel(label)
            .setLanguage(language)
            .setSampleMimeType(mimeType)
            .build()
        val group = TrackGroup("group-$id", format)
        return Tracks.Group(
            group,
            false,
            intArrayOf(C.FORMAT_HANDLED),
            booleanArrayOf(selected),
        )
    }
}
