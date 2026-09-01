package dev.neura.syncplay.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class PingTrackerTest {
    @Test
    fun computesRttAndForwardDelayWithAStableClock() {
        var now = 10.0
        val tracker = PingTracker { now }

        tracker.receive(echoedTimestamp = 9.0, senderRttSeconds = 0.0)

        assertEquals(1.0, tracker.rttSeconds, 0.0001)
        // With a one-second RTT and no server-side RTT, the estimated forward delay is 1.5 s.
        assertEquals(1.5, tracker.forwardDelaySeconds, 0.0001)

        now = 12.0
        tracker.receive(echoedTimestamp = 10.0, senderRttSeconds = 5.0)

        assertEquals(2.0, tracker.rttSeconds, 0.0001)
        // The moving average is 0.85*1 + 0.15*2 = 1.15; sender RTT >= measured uses half.
        assertEquals(0.575, tracker.forwardDelaySeconds, 0.0001)
    }

    @Test
    fun ignoresInvalidSamplesWithoutPoisoningThePreviousEstimate() {
        val tracker = PingTracker { 10.0 }
        tracker.receive(echoedTimestamp = 9.0, senderRttSeconds = 0.0)
        val originalRtt = tracker.rttSeconds
        val originalForwardDelay = tracker.forwardDelaySeconds

        tracker.receive(echoedTimestamp = 0.0, senderRttSeconds = 0.0)
        tracker.receive(echoedTimestamp = 11.0, senderRttSeconds = 0.0)
        tracker.receive(echoedTimestamp = 9.0, senderRttSeconds = -1.0)
        tracker.receive(echoedTimestamp = 9.0, senderRttSeconds = Double.NaN)
        tracker.receive(echoedTimestamp = 9.0, senderRttSeconds = Double.POSITIVE_INFINITY)

        assertEquals(originalRtt, tracker.rttSeconds, 0.0001)
        assertEquals(originalForwardDelay, tracker.forwardDelaySeconds, 0.0001)
    }

    @Test
    fun resetClearsRttAndForwardDelay() {
        val tracker = PingTracker { 10.0 }
        tracker.receive(echoedTimestamp = 9.0, senderRttSeconds = 0.0)

        tracker.reset()

        assertEquals(0.0, tracker.rttSeconds, 0.0)
        assertEquals(0.0, tracker.forwardDelaySeconds, 0.0)
    }
}
