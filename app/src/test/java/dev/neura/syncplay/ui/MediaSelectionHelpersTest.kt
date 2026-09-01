package dev.neura.syncplay.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaSelectionHelpersTest {
    @Test
    fun acceptsHttpAndHttpsUrlsWithAHost() {
        assertEquals(
            "http://example.test/video.mp4",
            validateHttpMediaUrl("  http://example.test/video.mp4  "),
        )
        assertEquals(
            "HTTPS://example.test/video.mp4",
            validateHttpMediaUrl("HTTPS://example.test/video.mp4"),
        )
    }

    @Test
    fun rejectsNonHttpSchemesAndUrlsWithoutAHost() {
        assertNull(validateHttpMediaUrl("file:///video.mp4"))
        assertNull(validateHttpMediaUrl("http://"))
        assertNull(validateHttpMediaUrl("https:///video.mp4"))
    }

    @Test
    fun stalePersistedUrisKeepOnlyTheActiveSelections() {
        val oldVideo = "content://files/video-1"
        val currentVideo = "content://files/video-2"
        val currentSubtitle = "content://files/subtitle-2"

        assertEquals(
            listOf(oldVideo),
            stalePersistedUris(
                persistedUris = listOf(oldVideo, currentVideo, currentSubtitle, oldVideo),
                activeUris = setOf(currentVideo, currentSubtitle),
            ),
        )
    }

    @Test
    fun staleSameUriMediaLoadCannotFinalizeTheNewRequest() {
        assertEquals(false, isCurrentMediaLoad(requestGeneration = 1L, currentGeneration = 2L))
        assertEquals(true, isCurrentMediaLoad(requestGeneration = 2L, currentGeneration = 2L))
    }

    @Test
    fun lateProgressFromThePreviousMediaCannotUpdateTheCurrentMedia() {
        assertEquals(
            false,
            isProgressForMedia(
                progressMediaIdentity = "content://files/video-1",
                currentMediaIdentity = "content://files/video-2",
            ),
        )
        assertEquals(
            true,
            isProgressForMedia(
                progressMediaIdentity = "content://files/video-2",
                currentMediaIdentity = "content://files/video-2",
            ),
        )
    }
}
