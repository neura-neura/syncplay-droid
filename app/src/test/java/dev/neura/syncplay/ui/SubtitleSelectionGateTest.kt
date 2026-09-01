package dev.neura.syncplay.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleSelectionGateTest {
    @Test
    fun selectingAnotherSubtitleReplacesInsteadOfAccumulatingTracks() {
        val selected = listOf("subtitle-1").replaceWithLatest("subtitle-2")

        assertEquals(listOf("subtitle-2"), selected)
    }

    @Test
    fun selectingAnotherSubtitleMakesTheOlderCompletionStale() {
        val gate = SubtitleSelectionGate()

        val first = gate.begin("content://media/video-1")
        val second = gate.begin("content://media/video-1")

        assertFalse(gate.isCurrent(first, "content://media/video-1"))
        assertTrue(gate.isCurrent(second, "content://media/video-1"))
    }

    @Test
    fun selectingAnotherMediaInvalidatesAnOlderSubtitleCompletion() {
        val gate = SubtitleSelectionGate()

        val first = gate.begin("content://media/video-1")
        gate.invalidate()
        val second = gate.begin("content://media/video-2")

        assertFalse(gate.isCurrent(first, "content://media/video-1"))
        assertFalse(gate.isCurrent(second, "content://media/video-1"))
        assertTrue(gate.isCurrent(second, "content://media/video-2"))
    }
}
