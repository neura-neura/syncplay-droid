package dev.neura.syncplay.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteSubtitleFontLoadGateTest {
    @Test
    fun aNewUrlMakesTheOlderCompletionStale() {
        val gate = RemoteSubtitleFontLoadGate()

        val first = gate.begin("https://example.test/old.css")
        val second = gate.begin("https://example.test/new.css")

        assertFalse(gate.isCurrent(first, "https://example.test/old.css"))
        assertTrue(gate.isCurrent(second, "https://example.test/new.css"))
    }

    @Test
    fun anExplicitFontChangeInvalidatesThePendingCompletion() {
        val gate = RemoteSubtitleFontLoadGate()
        val request = gate.begin("https://example.test/fonts.css")

        gate.invalidate()

        assertFalse(gate.isCurrent(request, "https://example.test/fonts.css"))
    }

    @Test
    fun aCompletionForTheCurrentUrlIsAccepted() {
        val gate = RemoteSubtitleFontLoadGate()
        val request = gate.begin("https://example.test/fonts.css")

        assertTrue(gate.isCurrent(request, "https://example.test/fonts.css"))
        assertFalse(gate.isCurrent(request, "https://example.test/other.css"))
    }
}
