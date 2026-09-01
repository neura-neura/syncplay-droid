package dev.neura.syncplay.protocol

import com.google.gson.JsonParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

class SyncplayConnectionIntegrationTest {
    @Test
    fun fatalServerErrorClearsLoggedStateBeforeClosingTheTransport() = runBlocking {
        val codec = SyncplayProtocolCodec()
        val serverSocket = ServerSocket(0)
        val releaseServer = CompletableDeferred<Unit>()
        val serverJob = launch(Dispatchers.IO) {
            serverSocket.use { listener ->
                listener.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val reader = socket.inputStream.bufferedReader(StandardCharsets.UTF_8)
                    val writer = socket.outputStream.bufferedWriter(StandardCharsets.UTF_8)
                    reader.readLine() // Hello
                    writer.write(
                        "{\"Hello\":{\"username\":\"FatalUser\",\"room\":{\"name\":\"FatalRoom\"}," +
                            "\"version\":\"1.7.5\",\"realversion\":\"1.7.5\",\"features\":{\"chat\":true}}}\r\n",
                    )
                    writer.write("{\"Error\":{\"message\":\"invalid credentials\"}}\r\n")
                    writer.flush()
                    releaseServer.await()
                }
            }
        }
        val connection = SyncplayConnection(codec)
        try {
            connection.connect(
                ConnectionConfig(
                    serverAddress = "127.0.0.1:${serverSocket.localPort}",
                    username = "FatalUser",
                    room = "FatalRoom",
                    preferTls = false,
                ),
            )

            val error = withTimeout(5_000) {
                connection.status.filterIsInstance<ConnectionStatus.Error>().first()
            }
            assertTrue(error.message.contains("invalid credentials"))
            assertFalse(connection.isLoggedForTest())

            // A terminal protocol error must not leave the public send methods authenticated.
            assertFalse(connection.sendChat("must not be sent"))
        } finally {
            connection.disconnect()
            releaseServer.complete(Unit)
            withTimeout(5_000) { serverJob.join() }
            connection.close()
        }
    }

    @Test
    fun disconnectThenReconnectDoesNotSendCommandsFromTheOldGeneration() = runBlocking {
        val codec = SyncplayProtocolCodec()
        val serverSocket = ServerSocket(0)
        val firstHello = CompletableDeferred<String>()
        val closeFirst = CompletableDeferred<Unit>()
        val secondHello = CompletableDeferred<String>()
        val unexpectedSecondLine = CompletableDeferred<String?>()
        val releaseSecond = CompletableDeferred<Unit>()
        val serverJob = launch(Dispatchers.IO) {
            serverSocket.use { listener ->
                listener.accept().use { firstSocket ->
                    firstSocket.soTimeout = 5_000
                    val reader = firstSocket.inputStream.bufferedReader(StandardCharsets.UTF_8)
                    val writer = firstSocket.outputStream.bufferedWriter(StandardCharsets.UTF_8)
                    firstHello.complete(reader.readLine() ?: "")
                    writer.write(
                        "{\"Hello\":{\"username\":\"ReconnectUser\",\"room\":{\"name\":\"ReconnectRoom\"}," +
                            "\"version\":\"1.7.5\",\"realversion\":\"1.7.5\",\"features\":{\"chat\":true}}}\r\n",
                    )
                    writer.flush()
                    closeFirst.await()
                }

                listener.accept().use { secondSocket ->
                    secondSocket.soTimeout = 500
                    val reader = secondSocket.inputStream.bufferedReader(StandardCharsets.UTF_8)
                    val writer = secondSocket.outputStream.bufferedWriter(StandardCharsets.UTF_8)
                    secondHello.complete(reader.readLine() ?: "")
                    writer.write(
                        "{\"Hello\":{\"username\":\"ReconnectUser\",\"room\":{\"name\":\"ReconnectRoom\"}," +
                            "\"version\":\"1.7.5\",\"realversion\":\"1.7.5\",\"features\":{\"chat\":true}}}\r\n",
                    )
                    writer.flush()
                    unexpectedSecondLine.complete(runCatching { reader.readLine() }.getOrNull())
                    releaseSecond.await()
                }
            }
        }
        val connection = SyncplayConnection(codec)
        val config = ConnectionConfig(
            serverAddress = "127.0.0.1:${serverSocket.localPort}",
            username = "ReconnectUser",
            room = "ReconnectRoom",
            preferTls = false,
        )
        try {
            connection.connect(config)
            withTimeout(5_000) {
                connection.status.filterIsInstance<ConnectionStatus.Connected>().first()
            }
            firstHello.await()

            connection.disconnect()
            // This must be ignored while the first generation is disconnected.
            connection.sendChat("stale command")
            closeFirst.complete(Unit)

            connection.connect(config)
            val observedSecondHello = withTimeout(5_000) { secondHello.await() }
            assertEquals("Hello", JsonParser.parseString(observedSecondHello).asJsonObject.entrySet().single().key)
            assertNull(withTimeout(5_000) { unexpectedSecondLine.await() })
        } finally {
            connection.disconnect()
            closeFirst.complete(Unit)
            releaseSecond.complete(Unit)
            withTimeout(5_000) { serverJob.join() }
            connection.close()
        }
    }

    @Test
    fun remoteApplierRunsBeforeHeartbeatAndReportsAcceptedLocalState() = runBlocking {
        val codec = SyncplayProtocolCodec()
        val serverSocket = ServerSocket(0)
        val responseLine = CompletableDeferred<String>()
        val releaseServer = CompletableDeferred<Unit>()
        val serverJob = launch(Dispatchers.IO) {
            serverSocket.use { listener ->
                listener.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val reader = socket.inputStream.bufferedReader(StandardCharsets.UTF_8)
                    val writer = socket.outputStream.bufferedWriter(StandardCharsets.UTF_8)
                    reader.readLine() // Hello
                    writer.write(
                        "{\"Hello\":{\"username\":\"AppliedUser\",\"room\":{\"name\":\"AppliedRoom\"}," +
                            "\"version\":\"1.7.5\",\"realversion\":\"1.7.5\",\"features\":{}}}\r\n",
                    )
                    writer.write(
                        "{\"State\":{\"ping\":{\"latencyCalculation\":10.0,\"serverRtt\":0}," +
                            "\"playstate\":{\"position\":10.0,\"paused\":true,\"doSeek\":true}}}\r\n",
                    )
                    writer.flush()
                    responseLine.complete(reader.readLine() ?: "")
                    releaseServer.await()
                }
            }
        }
        val connection = SyncplayConnection(codec)
        val applied = CompletableDeferred<RemotePlaybackState>()
        connection.setRemoteStateApplier { remote ->
            applied.complete(remote)
            LocalPlaybackState(positionSeconds = 9.5, paused = true)
        }
        try {
            connection.connect(
                ConnectionConfig(
                    serverAddress = "127.0.0.1:${serverSocket.localPort}",
                    username = "AppliedUser",
                    room = "AppliedRoom",
                    preferTls = false,
                ),
            )
            withTimeout(5_000) {
                connection.status.filterIsInstance<ConnectionStatus.Connected>().first()
            }
            val remote = withTimeout(5_000) { applied.await() }
            assertEquals(10.0, remote.positionSeconds, 0.0001)
            assertTrue(remote.doSeek)
            val response = codec.decode(withTimeout(5_000) { responseLine.await() }).state
            assertNotNull(response)
            assertEquals(9.5, response!!.positionSeconds!!, 0.0001)
            assertTrue(response.paused!!)
        } finally {
            connection.disconnect()
            releaseServer.complete(Unit)
            withTimeout(5_000) { serverJob.join() }
            connection.close()
        }
    }

    @Test
    fun tlsDeclineRequiresExplicitPlaintextOptIn() = runBlocking {
        val codec = SyncplayProtocolCodec()
        val serverSocket = ServerSocket(0)
        val tlsLine = CompletableDeferred<String>()
        val lineAfterDecline = CompletableDeferred<String?>()
        val serverJob = launch(Dispatchers.IO) {
            serverSocket.use { listener ->
                listener.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val reader = socket.inputStream.bufferedReader(StandardCharsets.UTF_8)
                    val writer = socket.outputStream.bufferedWriter(StandardCharsets.UTF_8)
                    tlsLine.complete(reader.readLine() ?: "")
                    writer.write("{\"TLS\":{\"startTLS\":\"false\"}}\r\n")
                    writer.flush()
                    lineAfterDecline.complete(runCatching { reader.readLine() }.getOrNull())
                }
            }
        }
        val connection = SyncplayConnection(codec)
        try {
            connection.connect(
                ConnectionConfig(
                    serverAddress = "127.0.0.1:${serverSocket.localPort}",
                    username = "SecureUser",
                    room = "SecureRoom",
                    preferTls = true,
                ),
            )
            val error = withTimeout(5_000) {
                connection.status.filterIsInstance<ConnectionStatus.Error>().first()
            }
            assertTrue(codec.isTlsAccepted(tlsLine.await()).not())
            assertTrue(error.message.contains("no ofrece TLS"))
            assertNull(withTimeout(5_000) { lineAfterDecline.await() })
        } finally {
            connection.close()
            withTimeout(5_000) { serverJob.join() }
        }
    }

    @Test
    fun explicitPlaintextOptInCompletesHelloStateAndHeartbeatFlow() = runBlocking {
        val codec = SyncplayProtocolCodec()
        val serverSocket = ServerSocket(0)
        val helloLine = CompletableDeferred<String>()
        val stateResponseLine = CompletableDeferred<String>()
        val localChangeLine = CompletableDeferred<String>()
        val localChangeAckLine = CompletableDeferred<String>()
        val releaseServer = CompletableDeferred<Unit>()

        val serverJob = launch(Dispatchers.IO) {
            serverSocket.use { listener ->
                listener.accept().use { socket ->
                    socket.soTimeout = 10_000
                    val reader = socket.inputStream.bufferedReader(StandardCharsets.UTF_8)
                    val writer = socket.outputStream.bufferedWriter(StandardCharsets.UTF_8)

                    helloLine.complete(reader.readLine() ?: "")
                    writer.write(
                        "{\"Hello\":{\"username\":\"IntegrationUser\",\"room\":{\"name\":\"IntegrationRoom\"}," +
                            "\"version\":\"1.7.5\",\"realversion\":\"1.7.5\"," +
                            "\"features\":{\"readiness\":true,\"chat\":true}}}\r\n",
                    )
                    writer.write(
                        "{\"State\":{\"ping\":{\"latencyCalculation\":123.456,\"serverRtt\":0}," +
                            "\"playstate\":{\"position\":42.0,\"paused\":true,\"doSeek\":false,\"setBy\":\"Alice\"}}}\r\n",
                    )
                    writer.flush()

                    stateResponseLine.complete(reader.readLine() ?: "")

                    // A local pause/play/seek is sent with the playstate and an incremented
                    // ignoringOnTheFly.client counter in the same State frame.  The server echoes
                    // that counter, after which the client must clear it on the next heartbeat.
                    localChangeLine.complete(reader.readLine() ?: "")
                    writer.write(
                        "{\"State\":{\"ping\":{\"latencyCalculation\":123.456,\"serverRtt\":0}," +
                            "\"ignoringOnTheFly\":{\"client\":1}}}\r\n",
                    )
                    writer.flush()
                    localChangeAckLine.complete(reader.readLine() ?: "")
                    releaseServer.await()
                }
            }
        }

        val connection = SyncplayConnection(codec = codec)
        connection.setLocalStateProvider { LocalPlaybackState(positionSeconds = 42.0, paused = true) }
        val connected = async {
            connection.status.first { it is ConnectionStatus.Connected } as ConnectionStatus.Connected
        }
        val playback = async {
            connection.events.filterIsInstance<ProtocolEvent.Playback>().first()
        }

        try {
            connection.connect(
                ConnectionConfig(
                    serverAddress = "127.0.0.1:${serverSocket.localPort}",
                    username = " Alice ",
                    room = " IntegrationRoom ",
                    password = "secret",
                    preferTls = false,
                ),
            )

            val status = withTimeout(5_000) { connected.await() }
            val playbackEvent = withTimeout(5_000) { playback.await() }
            val observedHello = withTimeout(5_000) { helloLine.await() }
            val observedStateResponse = withTimeout(5_000) { stateResponseLine.await() }

            assertEquals("1.7.5", status.serverVersion)
            assertFalse(status.secure)

            val helloPayload = JsonParser.parseString(observedHello).asJsonObject.getAsJsonObject("Hello")
            assertEquals("Alice", helloPayload.get("username").asString)
            assertEquals("IntegrationRoom", helloPayload.getAsJsonObject("room").get("name").asString)
            assertEquals("1.2.255", helloPayload.get("version").asString)
            assertEquals("5ebe2294ecd0e0f08eab7690d2a6ee69", helloPayload.get("password").asString)

            assertEquals(42.0, playbackEvent.value.positionSeconds, 0.0001)
            assertTrue(playbackEvent.value.paused)
            assertFalse(playbackEvent.value.doSeek)
            assertEquals("Alice", playbackEvent.value.setBy)

            val responseState = codec.decode(observedStateResponse).state
            assertNotNull(responseState)
            responseState!!
            assertEquals(42.0, responseState.positionSeconds!!, 0.0001)
            assertTrue(responseState.paused!!)
            assertEquals(123.456, responseState.latencyCalculation!!, 0.0001)
            assertNotNull(responseState.echoedClientTimestamp)
            // Client heartbeats carry clientRtt (the serverRtt field is server→client only).
            assertNull(responseState.serverRtt)
            val responsePing = JsonParser.parseString(observedStateResponse)
                .asJsonObject.getAsJsonObject("State").getAsJsonObject("ping")
            assertTrue(responsePing.has("clientRtt"))

            connection.sendPlaybackChange(
                LocalPlaybackState(positionSeconds = 99.0, paused = false),
                doSeek = true,
            )
            val observedLocalChange = withTimeout(5_000) { localChangeLine.await() }
            val localChangeState = JsonParser.parseString(observedLocalChange)
                .asJsonObject.getAsJsonObject("State")
            assertEquals(99.0, localChangeState.getAsJsonObject("playstate").get("position").asDouble, 0.0001)
            assertFalse(localChangeState.getAsJsonObject("playstate").get("paused").asBoolean)
            assertTrue(localChangeState.getAsJsonObject("playstate").get("doSeek").asBoolean)
            assertEquals(1, localChangeState.getAsJsonObject("ignoringOnTheFly").get("client").asInt)

            val observedLocalChangeAck = withTimeout(5_000) { localChangeAckLine.await() }
            val localChangeAckState = JsonParser.parseString(observedLocalChangeAck)
                .asJsonObject.getAsJsonObject("State")
            assertFalse(localChangeAckState.has("ignoringOnTheFly"))
        } finally {
            connection.disconnect()
            connected.cancel()
            playback.cancel()
            localChangeLine.cancel()
            localChangeAckLine.cancel()
            releaseServer.complete(Unit)
            withTimeout(5_000) { serverJob.join() }
            connection.close()
        }
    }

    private fun SyncplayConnection.isLoggedForTest(): Boolean =
        SyncplayConnection::class.java.getDeclaredField("logged").let { field ->
            field.isAccessible = true
            field.getBoolean(this)
        }
}
