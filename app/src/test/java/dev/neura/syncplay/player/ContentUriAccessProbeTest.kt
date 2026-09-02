package dev.neura.syncplay.player

import org.junit.Assert.assertEquals
import org.junit.Test

class ContentUriAccessProbeTest {
    @Test
    fun seekableDescriptorIsClassifiedAsSeekable() {
        assertEquals(
            SourceAccessClassification.SEEKABLE,
            ContentUriAccessProbe.classifyDescriptorAccess(
                seekable = true,
                isPipeOrSocket = false,
            ),
        )
    }

    @Test
    fun espipeLikeDescriptorIsClassifiedAsSequential() {
        assertEquals(
            SourceAccessClassification.SEQUENTIAL,
            ContentUriAccessProbe.classifyDescriptorAccess(
                seekable = false,
                isPipeOrSocket = false,
            ),
        )
    }

    @Test
    fun pipeOrSocketWinsEvenIfLseekUnexpectedlySucceeds() {
        assertEquals(
            SourceAccessClassification.SEQUENTIAL,
            ContentUriAccessProbe.classifyDescriptorAccess(
                seekable = true,
                isPipeOrSocket = true,
            ),
        )
    }

    @Test
    fun inconclusiveDescriptorIsUnknown() {
        assertEquals(
            SourceAccessClassification.UNKNOWN,
            ContentUriAccessProbe.classifyDescriptorAccess(
                seekable = null,
                isPipeOrSocket = false,
            ),
        )
    }
}
