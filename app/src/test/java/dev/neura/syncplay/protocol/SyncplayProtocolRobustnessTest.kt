package dev.neura.syncplay.protocol

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class SyncplayProtocolRobustnessTest {
    private val codec = SyncplayProtocolCodec()

    @Test
    fun rejectsPathologicalJsonNestingBeforeGson() {
        val nested = buildNestedObject(depth = 129)

        val error = assertThrows(ProtocolDecodeException::class.java) {
            codec.decode(nested)
        }

        assertTrue(error.message.orEmpty().contains("anidad", ignoreCase = true))
    }

    @Test
    fun startTlsProbeRejectsPathologicalNestingWithoutEnteringGson() {
        val nested = buildNestedObject(depth = 129)

        assertEquals(false, codec.isTlsAccepted(nested))
        assertEquals(false, codec.isTlsDeclined(nested))
    }

    @Test
    fun acceptsLegitimateNestedFeaturePayloadBelowTheGuard() {
        var nestedFeatures = "true"
        repeat(32) {
            nestedFeatures = "{\"nested\":$nestedFeatures}"
        }
        val line = """
            {"Hello":{"username":"Alice","room":{"name":"SyncRoom"},"realversion":"1.7.5","features":{"tree":$nestedFeatures}}}
        """.trimIndent()

        val event = codec.decode(line).events.single() as ProtocolEvent.Hello

        assertEquals("Alice", event.value.username)
        assertEquals("SyncRoom", event.value.room)
        assertTrue(event.value.features.containsKey("tree"))
    }

    @Test
    fun malformedJsonAndMalformedPayloadShapesBecomeControlledDecodeErrors() {
        listOf(
            "{\"State\":{",
            "{\"Hello\":[]}",
            "[]",
            "null",
        ).forEach { line ->
            assertThrows(ProtocolDecodeException::class.java) {
                codec.decode(line)
            }
        }
    }

    @Test
    fun bracesInsideStringsDoNotCountTowardsNesting() {
        val message = "{".repeat(256) + "}".repeat(256)
        val line = """
            {"Chat":{"username":"Alice","message":"${message.replace("\"", "\\\"")}"}}
        """.trimIndent()

        val event = codec.decode(line).events.single() as ProtocolEvent.Chat

        assertEquals(message, event.message)
    }

    @Test
    fun limitedLineReaderKeepsCrLfFramingAndExactByteLimit() {
        val exactValue = "x".repeat(65_536)
        // The limit applies to bytes before LF; a CR is part of that framed line.
        val input = ByteArrayInputStream("$exactValue\nnext\r\n".toByteArray())
        val reader = SyncplayConnection.LimitedLineReader(input, 65_536)

        assertEquals(exactValue, reader.readLine())
        assertEquals("next", reader.readLine())
        assertEquals(null, reader.readLine())
    }

    @Test
    fun limitedLineReaderRejectsTheFirstByteBeyondTheLimit() {
        val tooLong = "x".repeat(65_537) + "\n"
        val reader = SyncplayConnection.LimitedLineReader(
            ByteArrayInputStream(tooLong.toByteArray()),
            65_536,
        )

        assertThrows(Exception::class.java) { reader.readLine() }
    }

    @Test
    fun limitedLineReaderUsesBulkReadsBehindTheSocketBoundary() {
        val input = CountingInputStream("hello\r\n".toByteArray())
        val reader = SyncplayConnection.LimitedLineReader(input, 65_536)

        assertEquals("hello", reader.readLine())
        assertTrue("expected BufferedInputStream to use a bulk read", input.bulkReads > 0)
        assertEquals(0, input.singleByteReads)
    }

    private fun buildNestedObject(depth: Int): String = buildString {
        repeat(depth) { append("{\"nested\":") }
        append("null")
        repeat(depth) { append('}') }
    }

    private class CountingInputStream(
        private val bytes: ByteArray,
    ) : InputStream() {
        private var index = 0
        var singleByteReads = 0
            private set
        var bulkReads = 0
            private set

        override fun read(): Int {
            singleByteReads += 1
            return if (index < bytes.size) bytes[index++].toInt() and 0xff else -1
        }

        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            bulkReads += 1
            if (index >= bytes.size) return -1
            val count = minOf(length, bytes.size - index)
            bytes.copyInto(target, offset, index, index + count)
            index += count
            return count
        }
    }
}
