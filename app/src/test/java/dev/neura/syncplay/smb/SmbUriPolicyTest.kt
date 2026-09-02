package dev.neura.syncplay.smb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbUriPolicyTest {
    @Test
    fun pathPolicyNormalizesSeparatorsAndRejectsAbsolutePaths() {
        assertEquals("one/two/file.mkv", SmbPathPolicy.normalizeRelativePath("one\\two//file.mkv"))
        assertThrows { SmbPathPolicy.normalizeRelativePath("/one/file.mkv") }
        assertThrows { SmbPathPolicy.normalizeRelativePath("C:/one/file.mkv") }
        assertThrows { SmbPathPolicy.validateProfileId("profile/id") }
    }

    @Test
    fun profileRedactsSecretAndRegistryReturnsOnlySummaries() {
        val registry = SmbConnectionProfileRegistry()
        val summary = registry.register(
            SmbConnectionProfile(
                id = "nas",
                host = "192.0.2.10",
                username = "alice",
                password = "super-secret",
            ),
        )

        assertEquals("nas", summary.id)
        assertEquals("alice", summary.username)
        assertFalse(registry.require("nas").toString().contains("super-secret"))
        assertTrue(registry.unregister("nas"))
        assertFalse(registry.contains("nas"))
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
