package dev.neura.syncplay.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EncryptedPasswordPayloadTest {
    @Test
    fun payloadRoundTripPreservesIvAndCiphertextWithoutPlaintextEncoding() {
        val iv = ByteArray(12) { it.toByte() }
        val ciphertext = ByteArray(24) { (it * 3).toByte() }

        val encoded = EncryptedPasswordPayload.encode(iv, ciphertext)
        val decoded = requireNotNull(EncryptedPasswordPayload.decode(encoded))

        assertEquals("v1", encoded.substringBefore(':'))
        assertArrayEquals(iv, decoded.iv)
        assertArrayEquals(ciphertext, decoded.ciphertext)
    }

    @Test
    fun rejectsMalformedOrTruncatedPayloads() {
        assertNull(EncryptedPasswordPayload.decode(""))
        assertNull(EncryptedPasswordPayload.decode("v2:0011:2233"))
        assertNull(EncryptedPasswordPayload.decode("v1:not-hex:0011"))
        assertNull(EncryptedPasswordPayload.decode("v1:000102030405060708090a0b:0011"))
    }
}
