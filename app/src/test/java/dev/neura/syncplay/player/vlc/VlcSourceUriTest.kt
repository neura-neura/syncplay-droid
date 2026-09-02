package dev.neura.syncplay.player.vlc

import dev.neura.syncplay.smb.SmbConnectionProfile
import dev.neura.syncplay.smb.SmbLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VlcSourceUriTest {
    @Test
    fun smbLocationUsesBracketedIpv6AndNeverEmbedsCredentials() {
        val profile = SmbConnectionProfile(
            id = "nas",
            host = "2001:db8::10",
            port = 445,
            username = "alice",
            password = "super-secret",
        )
        val location = SmbLocation("nas", "Media", "Movies/episode one.mkv")

        val vlcMrl = smbLocationToLibVlcMrl(location, profile)

        assertEquals("smb://[2001:db8::10]:445/Media/Movies/episode%20one.mkv", vlcMrl)
        assertFalse(vlcMrl.contains("alice"))
        assertFalse(vlcMrl.contains("super-secret"))
    }

    @Test
    fun malformedHostCannotInjectUriAuthority() {
        val profile = SmbConnectionProfile(
            id = "nas",
            host = "host@example",
        )

        assertThrows {
            smbLocationToLibVlcMrl(SmbLocation("nas", "Media", "movie.mkv"), profile)
        }
    }

    @Test
    fun subtitleNamesMatchLabelsAndDecodedFileNames() {
        assertTrue(subtitleDescriptionMatches("Spanish (SRT)", "Spanish", "Sub Title.srt"))
        assertTrue(subtitleDescriptionMatches("Sub Title", null, "Sub Title.srt"))
        assertFalse(subtitleDescriptionMatches("French", "Spanish", "Sub Title.srt"))
    }

    @Test
    fun descriptorMrlContainsOnlyTheDescriptorNumber() {
        assertEquals("fd://17", fdMrlForDescriptor(17))
        assertThrows { fdMrlForDescriptor(-1) }
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
