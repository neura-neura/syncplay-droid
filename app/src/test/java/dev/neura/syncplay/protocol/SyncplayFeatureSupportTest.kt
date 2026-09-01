package dev.neura.syncplay.protocol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncplayFeatureSupportTest {
    @Test
    fun advertisedFeatureFlagOverridesVersionFallback() {
        assertFalse(supportsSyncplayFeature(mapOf("chat" to false), "9.0.0", "chat", "1.5.0"))
        assertTrue(supportsSyncplayFeature(mapOf("chat" to true), "1.0.0", "chat", "1.5.0"))
    }

    @Test
    fun missingFeatureFlagFallsBackToNumericServerVersion() {
        assertTrue(supportsSyncplayFeature(emptyMap(), "1.7.6", "chat", "1.5.0"))
        assertFalse(supportsSyncplayFeature(emptyMap(), "1.4.9", "chat", "1.5.0"))
    }
}
