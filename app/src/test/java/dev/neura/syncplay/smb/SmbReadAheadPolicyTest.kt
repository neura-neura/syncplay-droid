package dev.neura.syncplay.smb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbReadAheadPolicyTest {
    @Test
    fun effectiveCapacityNeverExceedsConfiguredRequestCap() {
        assertEquals(2 * 1024, SmbReadAheadPolicy.effectiveCapacity(2 * 1024, 8 * 1024))
        assertEquals(4 * 1024, SmbReadAheadPolicy.effectiveCapacity(2 * 1024 * 1024, 4 * 1024))
        assertEquals(0, SmbReadAheadPolicy.effectiveCapacity(0, 8 * 1024))
    }

    @Test
    fun prefetchLengthClampsToRemainingAndIntRange() {
        assertEquals(512, SmbReadAheadPolicy.windowLength(512, 2 * 1024))
        assertEquals(0, SmbReadAheadPolicy.windowLength(0, 2 * 1024))
        assertEquals(
            512,
            SmbReadAheadPolicy.prefetchLength(
                remainingBytes = 512,
                capacityBytes = 2 * 1024,
                maxChunkBytes = 8 * 1024,
            ),
        )
        assertEquals(
            2 * 1024,
            SmbReadAheadPolicy.prefetchLength(
                remainingBytes = Long.MAX_VALUE,
                capacityBytes = 2 * 1024,
                maxChunkBytes = 8 * 1024,
            ),
        )
        assertEquals(
            0,
            SmbReadAheadPolicy.prefetchLength(
                remainingBytes = 0,
                capacityBytes = 2 * 1024,
                maxChunkBytes = 8 * 1024,
            ),
        )
    }

    @Test
    fun readAheadIsUsedOnlyForRequestsSmallerThanTheWindow() {
        assertTrue(SmbReadAheadPolicy.shouldReadAhead(1 * 1024, 2 * 1024))
        assertFalse(SmbReadAheadPolicy.shouldReadAhead(2 * 1024, 2 * 1024))
        assertFalse(SmbReadAheadPolicy.shouldReadAhead(0, 2 * 1024))
    }
}
