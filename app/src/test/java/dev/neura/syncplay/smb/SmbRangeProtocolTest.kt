package dev.neura.syncplay.smb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbRangeProtocolTest {
    @Test
    fun parsesClosedOpenEndedAndSuffixRanges() {
        assertEquals(
            SmbRangeResult.Satisfied(SmbByteRange(10, 19)),
            SmbRangeProtocol.parseRangeHeader("bytes=10-19", 100),
        )
        assertEquals(
            SmbRangeResult.Satisfied(SmbByteRange(90, 99)),
            SmbRangeProtocol.parseRangeHeader("bytes=90-", 100),
        )
        assertEquals(
            SmbRangeResult.Satisfied(SmbByteRange(75, 99)),
            SmbRangeProtocol.parseRangeHeader("bytes=-25", 100),
        )
        // An end beyond EOF is legal and is clamped to the final byte.
        assertEquals(
            SmbRangeResult.Satisfied(SmbByteRange(95, 99)),
            SmbRangeProtocol.parseRangeHeader("bytes=95-1000", 100),
        )
    }

    @Test
    fun distinguishesAbsentMalformedAndUnsatisfiable() {
        assertEquals(SmbRangeResult.Absent, SmbRangeProtocol.parseRangeHeader(null, 100))
        assertEquals(SmbRangeResult.Absent, SmbRangeProtocol.parseRangeHeader("  ", 100))
        assertEquals(SmbRangeResult.Malformed, SmbRangeProtocol.parseRangeHeader("items=0-1", 100))
        assertEquals(SmbRangeResult.Malformed, SmbRangeProtocol.parseRangeHeader("bytes=0-1,4-5", 100))
        assertEquals(SmbRangeResult.Unsatisfiable, SmbRangeProtocol.parseRangeHeader("bytes=100-", 100))
        assertEquals(SmbRangeResult.Unsatisfiable, SmbRangeProtocol.parseRangeHeader("bytes=-0", 100))
        assertEquals(SmbRangeResult.Unsatisfiable, SmbRangeProtocol.parseRangeHeader("bytes=0-1", 0))
    }

    @Test
    fun responsePolicyEmitsDeterministic200206And416Metadata() {
        val full = SmbRangeProtocol.responsePolicy(SmbRangeResult.Absent, 100)
        assertEquals(200, full.status)
        assertEquals(100L, full.contentLength)
        assertEquals(null, full.contentRange)

        val partial = SmbRangeProtocol.responsePolicy(
            SmbRangeResult.Satisfied(SmbByteRange(10, 19)),
            100,
        )
        assertEquals(206, partial.status)
        assertEquals(10L, partial.contentLength)
        assertEquals("bytes 10-19/100", partial.contentRange)

        val rejected = SmbRangeProtocol.responsePolicy(SmbRangeResult.Malformed, 100)
        assertEquals(416, rejected.status)
        assertEquals(0L, rejected.contentLength)
        assertEquals("bytes */100", rejected.contentRange)
        assertTrue(SmbRangeProtocol.statusLine(416).startsWith("416"))
    }
}
