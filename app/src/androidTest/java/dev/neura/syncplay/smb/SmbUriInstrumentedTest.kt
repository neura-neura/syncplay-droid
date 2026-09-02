package dev.neura.syncplay.smb

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmbUriInstrumentedTest {
    @Test
    fun buildAndParseRoundTripKeepsPathWithoutCredentials() {
        val uri = SmbUri.build("living-room", "Media", "Shows\\Season 1\\Episode 01.mkv")

        assertEquals("syncplaysmb", uri.scheme)
        assertEquals("living-room", uri.host)
        assertEquals("Media", SmbUri.parse(uri).shareName)
        assertEquals("Shows/Season 1/Episode 01.mkv", SmbUri.parse(uri).path)
        assertFalse(uri.toString().contains("password", ignoreCase = true))
        assertFalse(uri.toString().contains("@"))
    }

    @Test
    fun parserRejectsUserInfoPortAndTraversal() {
        assertIllegalArgument { SmbUri.parse(Uri.parse("syncplaysmb://user@profile/Media/file.mkv")) }
        assertIllegalArgument { SmbUri.parse(Uri.parse("syncplaysmb://profile:445/Media/file.mkv")) }
        assertIllegalArgument { SmbUri.parse(Uri.parse("syncplaysmb://profile/Media/../secret.mkv")) }
        assertIllegalArgument { SmbUri.build("profile", "Media", "../secret.mkv") }
    }

    private fun assertIllegalArgument(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
