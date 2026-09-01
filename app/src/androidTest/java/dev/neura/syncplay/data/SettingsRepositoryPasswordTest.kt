package dev.neura.syncplay.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.neura.syncplay.protocol.ConnectionConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.charset.StandardCharsets

@RunWith(AndroidJUnit4::class)
class SettingsRepositoryPasswordTest {
    @Test
    fun androidKeystoreCipherUsesRandomIvAndRejectsTampering() {
        val cipher = AndroidKeystorePasswordCipher()
        val first = cipher.encrypt("same-password")
        val second = cipher.encrypt("same-password")

        assertNotEquals(first, second)
        assertEquals("same-password", cipher.decrypt(first))
        val replacement = if (first.last() == '0') '1' else '0'
        val tampered = first.dropLast(1) + replacement
        assertThrows(Exception::class.java) { cipher.decrypt(tampered) }
    }

    @Test
    fun passwordSurvivesRepositoryRecreationAndIsNotStoredAsPlaintext() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = SettingsRepository(context)
        val original = repository.connectionConfig.first()
        val password = "SMB-server-secret-${System.nanoTime()}"

        try {
            repository.saveConnection(
                ConnectionConfig(
                    serverAddress = "192.0.2.65:8999",
                    username = "tester",
                    room = "password-test",
                    password = password,
                    preferTls = false,
                ),
            )

            val reopened = SettingsRepository(context).connectionConfig.first()
            assertEquals(password, reopened.password)
            assertEquals("192.0.2.65:8999", reopened.serverAddress)

            val secretFile = context.filesDir.resolve("datastore/syncplay_secrets.preferences_pb")
            val bytes = secretFile.readBytes()
            val plaintext = password.toByteArray(StandardCharsets.UTF_8)
            assertFalse(bytes.containsSubsequence(plaintext))
        } finally {
            repository.saveConnection(original)
        }
    }

    @Test
    fun emptyPasswordRemovesRememberedSecret() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = SettingsRepository(context)
        val original = repository.connectionConfig.first()
        try {
            repository.saveConnection(original.copy(password = "temporary-secret"))
            repository.saveConnection(original.copy(password = ""))
            assertEquals("", SettingsRepository(context).connectionConfig.first().password)
        } finally {
            repository.saveConnection(original)
        }
    }
}

private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean {
    if (needle.isEmpty()) return true
    return indices.any { start ->
        start + needle.size <= size && needle.indices.all { offset ->
            this[start + offset] == needle[offset]
        }
    }
}
