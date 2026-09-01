package dev.neura.syncplay.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsTest {
    @Test
    fun parsesTcpAndSyncplayEndpoints() {
        assertEquals(
            ServerEndpoint("syncplay.pl", 8999),
            parseServerEndpoint("  syncplay://syncplay.pl:8999/ "),
        )
        assertEquals(
            ServerEndpoint("syncplay.pl", 8999),
            parseServerEndpoint("syncplay.pl"),
        )
        assertEquals(
            ServerEndpoint("2001:db8::1", 9443),
            parseServerEndpoint("tcp://[2001:db8::1]:9443"),
        )
        assertEquals(
            ServerEndpoint("2001:db8::1", 8999),
            parseServerEndpoint("2001:db8::1"),
        )
    }

    @Test
    fun endpointDisplayBracketsIpv6() {
        assertEquals("[2001:db8::1]:9443", ServerEndpoint("2001:db8::1", 9443).displayName)
        assertEquals("syncplay.pl:8999", ServerEndpoint("syncplay.pl", 8999).displayName)
    }

    @Test
    fun quickPortSelectionPreservesTheConfiguredHost() {
        assertEquals(
            "192.0.2.65:8996",
            serverAddressWithPort("192.0.2.65:8999", 8996),
        )
        assertEquals(
            "my-syncplay.example:8997",
            serverAddressWithPort("my-syncplay.example:8999", 8997),
        )
        assertEquals(
            "my-syncplay.example:8995",
            serverAddressWithPort("syncplay://my-syncplay.example/", 8995),
        )
    }

    @Test
    fun quickPortSelectionFormatsIpv6WithoutLosingTheAddress() {
        assertEquals(
            "[2001:db8::1]:8996",
            serverAddressWithPort("[2001:db8::1]:8999", 8996),
        )
        assertEquals(
            "[2001:db8::1]:8998",
            serverAddressWithPort("2001:db8::1", 8998),
        )
    }

    @Test
    fun rejectsEmptyAndOutOfRangeEndpoints() {
        listOf("", "   ", ":8999", "[::1", "host:abc", "host:0", "host:65536").forEach { value ->
            try {
                parseServerEndpoint(value)
                throw AssertionError("Expected endpoint '$value' to be rejected")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun connectionStatusCarriesTlsAndServerVersion() {
        val connected = ConnectionStatus.Connected("syncplay.pl:8999", secure = true, serverVersion = "1.7.5")
        assertTrue(connected.secure)
        assertEquals("1.7.5", connected.serverVersion)
    }
}
