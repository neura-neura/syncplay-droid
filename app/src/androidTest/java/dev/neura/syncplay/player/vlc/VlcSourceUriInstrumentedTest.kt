package dev.neura.syncplay.player.vlc

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.neura.syncplay.smb.SmbConnectionProfile
import dev.neura.syncplay.smb.SmbUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VlcSourceUriInstrumentedTest {
    @Test
    fun smbLocationUsesBracketedIpv6AndNeverEmbedsCredentials() {
        val profile = SmbConnectionProfile(
            id = "nas",
            host = "2001:db8::10",
            port = 445,
            username = "alice",
            password = "super-secret",
        )
        val appUri = SmbUri.build("nas", "Media", "Movies/episode one.mkv")

        val vlcUri = appUri.toLibVlcUri(profile)

        assertEquals("smb", vlcUri.scheme)
        assertEquals("[2001:db8::10]:445", vlcUri.encodedAuthority)
        assertEquals("/Media/Movies/episode%20one.mkv", vlcUri.encodedPath)
        assertFalse(vlcUri.toString().contains("alice"))
        assertFalse(vlcUri.toString().contains("super-secret"))
    }

    @Test
    fun malformedHostCannotInjectUriAuthority() {
        val profile = SmbConnectionProfile(
            id = "nas",
            host = "host@example",
        )
        val appUri = SmbUri.build("nas", "Media", "movie.mkv")

        assertThrows { appUri.toLibVlcUri(profile) }
    }

    @Test
    fun subtitleNamesMatchLabelsAndDecodedFileNames() {
        val subtitle = VlcExternalSubtitle(
            id = "sub-1",
            uri = Uri.parse("content://provider/Sub%20Title.srt"),
            label = "Spanish",
        )

        assertTrue(descriptionMatchesSubtitle("Spanish (SRT)", subtitle))
        assertTrue(descriptionMatchesSubtitle("Sub Title", subtitle))
        assertFalse(descriptionMatchesSubtitle("French", subtitle))
    }

    @Test
    fun descriptorUriUsesVlcFdScheme() {
        assertEquals("fd://42", fdUriForDescriptor(42).toString())
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
