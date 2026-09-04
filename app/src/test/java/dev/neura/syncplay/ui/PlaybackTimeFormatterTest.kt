package dev.neura.syncplay.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackTimeFormatterTest {
    @Test
    fun formatsHoursMinutesAndSecondsWithoutDecimalMinutes() {
        assertEquals("00:00:00", formatPlaybackTimeMs(-1L))
        assertEquals("00:00:00", formatPlaybackTimeMs(0L))
        assertEquals("00:00:01", formatPlaybackTimeMs(1_999L))
        assertEquals("00:18:12", formatPlaybackTimeMs(18L * 60_000L + 12_000L))
        assertEquals("01:56:36", formatPlaybackTimeMs(116L * 60_000L + 36_000L))
        assertEquals("125:03:09", formatPlaybackTimeMs(125L * 3_600_000L + 3L * 60_000L + 9_000L))
    }

    @Test
    fun togglesBetweenElapsedAndRemainingWhileKeepingTotalDuration() {
        val position = 18L * 60_000L + 12_000L
        val duration = 116L * 60_000L + 36_000L

        assertEquals("00:18:12 / 01:56:36", playbackTimeLabel(position, duration, false))
        assertEquals("-01:38:24 / 01:56:36", playbackTimeLabel(position, duration, true))
        assertEquals("-00:00:00 / 01:56:36", playbackTimeLabel(duration + 5_000L, duration, true))
    }
}
