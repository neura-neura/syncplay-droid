package dev.neura.syncplay.ui

import java.util.Locale

/** Formats player time without rounding away seconds or collapsing hours into decimal minutes. */
internal fun formatPlaybackTimeMs(valueMs: Long): String {
    val totalSeconds = valueMs.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds)
}

/** Builds the tappable elapsed/remaining readout shown next to the seek bar. */
internal fun playbackTimeLabel(
    positionMs: Long,
    durationMs: Long,
    showRemaining: Boolean,
): String {
    val duration = durationMs.coerceAtLeast(0L)
    val position = if (duration > 0L) {
        positionMs.coerceIn(0L, duration)
    } else {
        positionMs.coerceAtLeast(0L)
    }
    val leading = if (showRemaining) {
        "-${formatPlaybackTimeMs((duration - position).coerceAtLeast(0L))}"
    } else {
        formatPlaybackTimeMs(position)
    }
    return "$leading / ${formatPlaybackTimeMs(duration)}"
}
