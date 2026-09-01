package dev.neura.syncplay.protocol

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncplayProtocolCodecTest {
    @Test
    fun listRequestKeepsItsRequiredNullPayload() {
        assertEquals("{\"List\":null}", codec.requestList())
    }

    private val codec = SyncplayProtocolCodec()

    @Test
    fun tlsRequestUsesStartTlsCommand() {
        val root = JsonParser.parseString(codec.tlsRequest()).asJsonObject
        assertEquals("send", root.getAsJsonObject("TLS").get("startTLS").asString)
        assertTrue(codec.isTlsAccepted("{\"TLS\":{\"startTLS\":\"true\"}}"))
        assertFalse(codec.isTlsAccepted("{\"TLS\":{\"startTLS\":\"false\"}}"))
    }

    @Test
    fun helloTrimsIdentityAndUsesMd5CompatibilityShape() {
        val hello = codec.hello(
            ConnectionConfig(
                serverAddress = "syncplay.pl:8999",
                username = " Bob ",
                room = " SyncRoom ",
                password = "secret",
            ),
        )
        val payload = JsonParser.parseString(hello).asJsonObject.getAsJsonObject("Hello")

        assertEquals("Bob", payload.get("username").asString)
        assertEquals("5ebe2294ecd0e0f08eab7690d2a6ee69", payload.get("password").asString)
        assertEquals("SyncRoom", payload.getAsJsonObject("room").get("name").asString)
        // Syncplay sends this alias so a modern client can still connect to a 1.2.x server.
        assertEquals("1.2.255", payload.get("version").asString)
        assertEquals(SyncplayProtocolCodec.CLIENT_PROTOCOL_VERSION, payload.get("realversion").asString)
        assertTrue(payload.getAsJsonObject("features").get("sharedPlaylists").asBoolean)
        assertEquals("GUI", payload.getAsJsonObject("features").get("uiMode").asString)
    }

    @Test
    fun fileAndReadyCommandsContainWireFields() {
        val file = JsonParser.parseString(
            codec.setFile(MediaDescriptor("movie.mkv", 123.45, 456789L)),
        ).asJsonObject.getAsJsonObject("Set").getAsJsonObject("file")
        assertEquals("movie.mkv", file.get("name").asString)
        assertEquals(123.45, file.get("duration").asDouble, 0.0001)
        assertEquals(456789L, file.get("size").asLong)
        assertFalse(file.has("path"))

        val ready = JsonParser.parseString(codec.setReady(true, manuallyInitiated = false))
            .asJsonObject.getAsJsonObject("Set").getAsJsonObject("ready")
        assertTrue(ready.get("isReady").asBoolean)
        assertFalse(ready.get("manuallyInitiated").asBoolean)
    }

    @Test
    fun chatUsesStringOnClientAndObjectOnServerBroadcast() {
        val outbound = JsonParser.parseString(codec.chat("hello")).asJsonObject
        assertEquals("hello", outbound.get("Chat").asString)

        val inbound = codec.decode(
            """{"Chat":{"username":"Bob","message":"hello"}}""",
        ).events.single() as ProtocolEvent.Chat
        assertEquals("Bob", inbound.username)
        assertEquals("hello", inbound.message)
    }

    @Test
    fun roomChangeCarriesNameAndOptionalControllerPassword() {
        val room = JsonParser.parseString(codec.setRoom("SyncRoom", "AB-123-456"))
            .asJsonObject.getAsJsonObject("Set").getAsJsonObject("room")
        assertEquals("SyncRoom", room.get("name").asString)
        assertEquals("AB-123-456", room.get("password").asString)
    }

    @Test
    fun stateOmitsPlaystateWhileIgnoringLocalChangeAndCarriesPing() {
        val root = JsonParser.parseString(
            codec.state(
                local = LocalPlaybackState(10.0, paused = false),
                latencyCalculation = 5.0,
                clientRtt = 0.25,
                clientTimestamp = 6.0,
                clientIgnoringOnTheFly = 1,
                serverIgnoringOnTheFly = 0,
                doSeek = true,
            ),
        ).asJsonObject
        val state = root.getAsJsonObject("State")
        assertFalse(state.has("playstate"))
        assertEquals(5.0, state.getAsJsonObject("ping").get("latencyCalculation").asDouble, 0.0001)
        assertEquals(6.0, state.getAsJsonObject("ping").get("clientLatencyCalculation").asDouble, 0.0001)
        assertEquals(0.25, state.getAsJsonObject("ping").get("clientRtt").asDouble, 0.0001)
        assertEquals(1, state.getAsJsonObject("ignoringOnTheFly").get("client").asInt)
    }

    @Test
    fun decodesObservedListWithFileControllerReadinessAndFeatures() {
        val line = """
            {"List":{"SyncRoom":{"Alice":{"position":0,"file":{"name":"movie.mkv","duration":123.45,"size":456789},"controller":true,"isReady":true,"features":{"sharedPlaylists":true,"uiMode":"GUI"}}}}}
        """.trimIndent()

        val decoded = codec.decode(line)
        assertNull(decoded.state)
        val event = decoded.events.single() as ProtocolEvent.UserList
        val user = event.users.single()
        assertEquals("Alice", user.username)
        assertEquals("SyncRoom", user.room)
        assertEquals(MediaDescriptor("movie.mkv", 123.45, 456789L), user.file)
        assertTrue(user.isController)
        assertEquals(true, user.isReady)
        assertEquals("GUI", user.features["uiMode"])
    }

    @Test
    fun decodesObservedJoinFileAndLeaveSetDeltas() {
        val joined = codec.decode(
            """{"Set":{"user":{"Bob":{"room":{"name":"SyncRoom"},"event":{"joined":true,"version":"1.7.5","features":{"chat":true}}}}}}""",
        ).events.single() as ProtocolEvent.UserJoinedOrUpdated
        assertTrue(joined.joined)
        assertEquals("Bob", joined.user.username)
        assertEquals("SyncRoom", joined.user.room)
        assertNull(joined.user.file)
        assertFalse(joined.fileProvided)

        val updated = codec.decode(
            """{"Set":{"user":{"Bob":{"room":{"name":"SyncRoom"},"file":{"name":"movie.mkv","duration":123.45,"size":456789}}}}}""",
        ).events.single() as ProtocolEvent.UserJoinedOrUpdated
        assertFalse(updated.joined)
        assertTrue(updated.fileProvided)
        assertEquals(MediaDescriptor("movie.mkv", 123.45, 456789L), updated.user.file)

        val cleared = codec.decode(
            """{"Set":{"user":{"Bob":{"file":{},"controller":false,"isReady":false,"features":{}}}}}""",
        ).events.single() as ProtocolEvent.UserJoinedOrUpdated
        assertTrue(cleared.fileProvided)
        assertNull(cleared.user.file)
        assertTrue(cleared.controllerProvided)
        assertFalse(cleared.user.isController)
        assertTrue(cleared.readinessProvided)
        assertEquals(false, cleared.user.isReady)
        assertTrue(cleared.featuresProvided)
        assertTrue(cleared.user.features.isEmpty())

        val left = codec.decode(
            """{"Set":{"user":{"Bob":{"room":{"name":"SyncRoom"},"event":{"left":true}}}}}""",
        ).events.single() as ProtocolEvent.UserLeft
        assertEquals("Bob", left.username)
    }

    @Test
    fun decodesReadyPlaylistRoomAndFeatureSetMessages() {
        val line = """
            {"Set":{"room":{"name":"OtherRoom"},"ready":{"username":"Bob","isReady":false,"manuallyInitiated":true,"setBy":"Alice"},"playlistChange":{"user":"Alice","files":["one.mkv","two.mkv"]},"playlistIndex":{"user":"Alice","index":1},"features":{"username":"Bob","features":{"chat":false}}}}
        """.trimIndent()
        val events = codec.decode(line).events

        assertTrue(events.contains(ProtocolEvent.RoomChanged("OtherRoom")))
        assertTrue(
            events.contains(
                ProtocolEvent.ReadyChanged("Bob", false, manuallyInitiated = true, setBy = "Alice"),
            ),
        )
        assertTrue(events.contains(ProtocolEvent.PlaylistChanged(listOf("one.mkv", "two.mkv"), "Alice")))
        assertTrue(events.contains(ProtocolEvent.PlaylistIndexChanged(1, "Alice")))
        val features = events.single { it is ProtocolEvent.FeaturesChanged } as ProtocolEvent.FeaturesChanged
        assertEquals("Bob", features.username)
        assertEquals(false, features.features["chat"])
    }

    @Test
    fun initialServerSetAllowsNullReadinessAndPlaylistIndex() {
        val ready = codec.decode(
            """{"Set":{"ready":{"username":"CodexAuditXYZ","isReady":null,"manuallyInitiated":false}}}""",
        )
        assertTrue(ready.events.isEmpty())

        val playlist = codec.decode(
            """{"Set":{"playlistChange":{"user":null,"files":[]},"playlistIndex":{"user":null,"index":null}}}""",
        )
        assertEquals(1, playlist.events.size)
        assertEquals(ProtocolEvent.PlaylistChanged(emptyList(), null), playlist.events.single())
    }

    @Test
    fun decodesObservedStatePingAndIgnoreCounters() {
        val line = """
            {"State":{"ping":{"latencyCalculation":1788119405.609106,"serverRtt":0,"clientLatencyCalculation":1788119404.5},"playstate":{"position":60.0,"paused":false,"doSeek":true,"setBy":"Bob"},"ignoringOnTheFly":{"server":1,"client":2}}}
        """.trimIndent()
        val state = codec.decode(line).state
        assertNotNull(state)
        state!!
        assertEquals(60.0, state.positionSeconds!!, 0.0001)
        assertFalse(state.paused!!)
        assertTrue(state.doSeek)
        assertEquals("Bob", state.setBy)
        assertEquals(1788119405.609106, state.latencyCalculation!!, 0.0001)
        assertEquals(1788119404.5, state.echoedClientTimestamp!!, 0.0001)
        assertEquals(0.0, state.serverRtt!!, 0.0001)
        assertEquals(1, state.serverIgnoringOnTheFly)
        assertEquals(2, state.clientIgnoringOnTheFly)
    }

    @Test
    fun stateWithoutPlaystateStillCarriesPing() {
        val state = codec.decode(
            """{"State":{"ping":{"latencyCalculation":12.5,"serverRtt":0.25}}}""",
        ).state
        assertNotNull(state)
        assertNull(state!!.positionSeconds)
        assertNull(state.paused)
        assertFalse(state.doSeek)
        assertEquals(12.5, state.latencyCalculation!!, 0.0001)
        assertEquals(0.25, state.serverRtt!!, 0.0001)
    }

    @Test
    fun decodesLegacyForwardDelayAlias() {
        val state = codec.decode(
            """{"State":{"ping":{"yourLatency":0.75,"senderLatency":0.5},"playstate":{"position":1.0,"paused":true}}}""",
        ).state
        assertNotNull(state)
        assertEquals(0.75, state!!.legacyForwardDelaySeconds!!, 0.0001)
    }
}
