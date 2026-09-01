package dev.neura.syncplay.protocol

import kotlin.math.max

/** Port of Syncplay's PingService latency/forward-delay estimator. */
class PingTracker(
    private val nowSeconds: () -> Double = { System.currentTimeMillis() / 1_000.0 },
) {
    var rttSeconds: Double = 0.0
        private set
    var forwardDelaySeconds: Double = 0.0
        private set
    private var averageRttSeconds: Double = 0.0

    fun timestamp(): Double = nowSeconds()

    fun receive(echoedTimestamp: Double?, senderRttSeconds: Double?) {
        if (echoedTimestamp == null || senderRttSeconds == null || echoedTimestamp == 0.0) return
        val measuredRtt = nowSeconds() - echoedTimestamp
        if (!measuredRtt.isFinite() || measuredRtt < 0.0 ||
            !senderRttSeconds.isFinite() || senderRttSeconds < 0.0
        ) return
        rttSeconds = measuredRtt
        if (averageRttSeconds == 0.0) averageRttSeconds = measuredRtt
        averageRttSeconds = averageRttSeconds * MOVING_AVERAGE_WEIGHT +
            measuredRtt * (1.0 - MOVING_AVERAGE_WEIGHT)
        forwardDelaySeconds = if (senderRttSeconds < measuredRtt) {
            averageRttSeconds / 2.0 + (measuredRtt - senderRttSeconds)
        } else {
            averageRttSeconds / 2.0
        }
        forwardDelaySeconds = max(0.0, forwardDelaySeconds)
    }

    fun reset() {
        rttSeconds = 0.0
        forwardDelaySeconds = 0.0
        averageRttSeconds = 0.0
    }

    private companion object {
        const val MOVING_AVERAGE_WEIGHT = 0.85
    }
}
