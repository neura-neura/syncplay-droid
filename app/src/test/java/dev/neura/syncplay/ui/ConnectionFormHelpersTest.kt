package dev.neura.syncplay.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionFormHelpersTest {
    @Test
    fun changingOnlyPortKeepsTheServerPassword() {
        assertFalse(shouldClearPasswordForServerHost("192.0.2.8", "192.0.2.8:8996"))
    }

    @Test
    fun changingHostClearsTheServerPassword() {
        assertTrue(shouldClearPasswordForServerHost("old.example", "new.example:8999"))
    }

    @Test
    fun invalidIntermediateAddressKeepsPasswordUntilTheNewHostIsValid() {
        assertFalse(shouldClearPasswordForServerHost("old.example", ":8999"))
        assertTrue(shouldClearPasswordForServerHost("old.example", "new.example:8999"))
    }
}
