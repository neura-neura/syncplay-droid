package dev.neura.syncplay.protocol

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import java.security.MessageDigest

data class IncomingStateEnvelope(
    val positionSeconds: Double?,
    val paused: Boolean?,
    val doSeek: Boolean,
    val setBy: String?,
    val latencyCalculation: Double?,
    val echoedClientTimestamp: Double?,
    val serverRtt: Double?,
    val legacyForwardDelaySeconds: Double?,
    val serverIgnoringOnTheFly: Int?,
    val clientIgnoringOnTheFly: Int?,
)

data class DecodedLine(
    val events: List<ProtocolEvent> = emptyList(),
    val state: IncomingStateEnvelope? = null,
)

/**
 * Newline framing is handled by [SyncplayConnection]; this class only knows standard Syncplay JSON.
 * The wire shapes intentionally mirror syncplay/protocols.py from the desktop client.
 */
class SyncplayProtocolCodec(
    private val gson: Gson = Gson(),
) {
    private val mapType = object : TypeToken<Map<String, Any?>>() {}.type

    fun tlsRequest(): String = gson.toJson(
        JsonObject().apply {
            add("TLS", JsonObject().apply { addProperty("startTLS", "send") })
        },
    )

    fun hello(config: ConnectionConfig): String {
        val payload = JsonObject().apply {
            addProperty("username", config.username.trim())
            if (config.password.isNotEmpty()) {
                addProperty("password", md5(config.password))
            }
            add("room", JsonObject().apply { addProperty("name", config.room.trim()) })
            // Compatibility alias used by Syncplay so modern clients still work with 1.2.x servers.
            addProperty("version", "1.2.255")
            addProperty("realversion", CLIENT_PROTOCOL_VERSION)
            add("features", JsonObject().apply {
                addProperty("sharedPlaylists", true)
                addProperty("chat", true)
                addProperty("uiMode", "GUI")
                addProperty("featureList", true)
                addProperty("readiness", true)
                addProperty("managedRooms", true)
                // We understand persistent-room payloads but do not persist remote playlists locally.
                addProperty("persistentRooms", false)
                addProperty("setOthersReadiness", true)
            })
        }
        return command("Hello", payload)
    }

    // Gson omits JsonNull object members unless serializeNulls() is enabled, while the Syncplay
    // wire command is specifically {"List": null}. Keep this frame explicit and deterministic.
    fun requestList(): String = "{\"List\":null}"

    fun setRoom(room: String, controllerPassword: String? = null): String = setCommand(
        "room",
        JsonObject().apply {
            addProperty("name", room)
            controllerPassword?.takeIf { it.isNotBlank() }?.let { addProperty("password", it) }
        },
    )

    fun setFile(file: MediaDescriptor): String = setCommand(
        "file",
        JsonObject().apply {
            addProperty("name", file.name)
            addProperty("duration", file.durationSeconds)
            addProperty("size", file.sizeBytes)
        },
    )

    fun setReady(isReady: Boolean, manuallyInitiated: Boolean = true): String = setCommand(
        "ready",
        JsonObject().apply {
            addProperty("isReady", isReady)
            addProperty("manuallyInitiated", manuallyInitiated)
        },
    )

    fun authenticateController(room: String, password: String): String = setCommand(
        "controllerAuth",
        JsonObject().apply {
            addProperty("room", room)
            addProperty("password", password)
        },
    )

    fun chat(message: String): String = command("Chat", gson.toJsonTree(message))

    fun state(
        local: LocalPlaybackState?,
        latencyCalculation: Double?,
        clientRtt: Double,
        clientTimestamp: Double,
        clientIgnoringOnTheFly: Int,
        serverIgnoringOnTheFly: Int,
        doSeek: Boolean,
        forcePlaystate: Boolean = false,
    ): String {
        val state = JsonObject()
        if (local != null && (forcePlaystate || clientIgnoringOnTheFly == 0 || serverIgnoringOnTheFly != 0)) {
            state.add("playstate", JsonObject().apply {
                addProperty("position", local.positionSeconds)
                addProperty("paused", local.paused)
                if (doSeek) addProperty("doSeek", true)
            })
        }
        state.add("ping", JsonObject().apply {
            latencyCalculation?.let { addProperty("latencyCalculation", it) }
            addProperty("clientLatencyCalculation", clientTimestamp)
            addProperty("clientRtt", clientRtt)
        })
        if (clientIgnoringOnTheFly != 0 || serverIgnoringOnTheFly != 0) {
            state.add("ignoringOnTheFly", JsonObject().apply {
                if (serverIgnoringOnTheFly != 0) addProperty("server", serverIgnoringOnTheFly)
                if (clientIgnoringOnTheFly != 0) addProperty("client", clientIgnoringOnTheFly)
            })
        }
        return command("State", state)
    }

    fun decode(line: String): DecodedLine {
        // Gson's tree parser is recursive.  Validate the wire text before handing it to Gson so a
        // malicious peer cannot turn pathological nesting into a StackOverflowError.  The Syncplay
        // frames emitted by this client are only a few levels deep; this limit leaves ample room
        // for legitimate feature payloads while keeping parser recursion bounded.
        validateJsonStructure(line)
        val root = try {
            JsonParser.parseString(line).asJsonObject
        } catch (error: StackOverflowError) {
            throw ProtocolDecodeException("El JSON del servidor está demasiado anidado", error)
        } catch (error: Exception) {
            throw ProtocolDecodeException("El JSON del servidor no es válido", error)
        }

        return try {
            decodeRoot(root)
        } catch (error: StackOverflowError) {
            throw ProtocolDecodeException("El JSON del servidor está demasiado anidado", error)
        } catch (error: ProtocolDecodeException) {
            throw error
        } catch (error: Exception) {
            throw ProtocolDecodeException("La carga JSON del servidor no es válida", error)
        }
    }

    private fun decodeRoot(root: JsonObject): DecodedLine {
        val events = mutableListOf<ProtocolEvent>()
        var state: IncomingStateEnvelope? = null
        root.entrySet().forEach { (command, payload) ->
            when (command) {
                "Hello" -> decodeHello(payload.asJsonObject)?.let { events += it }
                "Set" -> events += decodeSet(payload.asJsonObject)
                "List" -> events += ProtocolEvent.UserList(decodeUserList(payload.asJsonObject))
                "State" -> state = decodeState(payload.asJsonObject)
                "Chat" -> decodeChat(payload)?.let { events += it }
                "Error" -> {
                    val message = payload.asJsonObject.string("message") ?: "Error desconocido del servidor"
                    events += ProtocolEvent.Error(message)
                }
            }
        }
        return DecodedLine(events, state)
    }

    /**
     * Check only the JSON container structure and string quoting.  Full syntax and types remain
     * Gson's responsibility; this pass exists specifically to bound recursive parser depth and to
     * turn obviously truncated frames into a controlled [ProtocolDecodeException].
     */
    private fun validateJsonStructure(line: String) {
        var depth = 0
        var inString = false
        var escaped = false

        line.forEach { character ->
            if (inString) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> inString = false
                }
                return@forEach
            }

            when (character) {
                '"' -> inString = true
                '{', '[' -> {
                    depth += 1
                    if (depth > MAX_JSON_NESTING) {
                        throw ProtocolDecodeException("El JSON del servidor está demasiado anidado")
                    }
                }
                '}', ']' -> {
                    depth -= 1
                    if (depth < 0) {
                        throw ProtocolDecodeException("El JSON del servidor está truncado")
                    }
                }
            }
        }

        if (inString || escaped || depth != 0) {
            throw ProtocolDecodeException("El JSON del servidor está truncado")
        }
    }

    fun isTlsAccepted(line: String): Boolean = runCatching {
        validateJsonStructure(line)
        JsonParser.parseString(line).asJsonObject
            .getAsJsonObject("TLS")
            ?.string("startTLS")
            ?.equals("true", ignoreCase = true) == true
    }.getOrDefault(false)

    fun isTlsDeclined(line: String): Boolean = runCatching {
        validateJsonStructure(line)
        JsonParser.parseString(line).asJsonObject
            .getAsJsonObject("TLS")
            ?.string("startTLS")
            ?.equals("false", ignoreCase = true) == true
    }.getOrDefault(false)

    private fun decodeHello(payload: JsonObject): ProtocolEvent.Hello? {
        val username = payload.string("username") ?: return null
        val room = payload.obj("room")?.string("name") ?: return null
        val version = payload.string("realversion") ?: payload.string("version") ?: return null
        val motd = payload.string("motd")?.takeIf { it.isNotBlank() }
        val features = payload.obj("features")?.toMap() ?: emptyMap()
        return ProtocolEvent.Hello(ServerHello(username, room, version, motd, features))
    }

    private fun decodeSet(payload: JsonObject): List<ProtocolEvent> {
        val events = mutableListOf<ProtocolEvent>()
        payload.entrySet().forEach { (setting, value) ->
            when (setting) {
                "room" -> value.asJsonObject.string("name")?.let { events += ProtocolEvent.RoomChanged(it) }
                "user" -> decodeUserDeltas(value.asJsonObject, events)
                "ready" -> {
                    val ready = value.asJsonObject
                    val username = ready.string("username")
                    val isReady = ready.bool("isReady")
                    if (username != null && isReady != null) {
                        events += ProtocolEvent.ReadyChanged(
                            username = username,
                            isReady = isReady,
                            manuallyInitiated = ready.bool("manuallyInitiated") ?: true,
                            setBy = ready.string("setBy"),
                        )
                    }
                }
                "playlistChange" -> {
                    val change = value.asJsonObject
                    val files = change.arrayStrings("files")
                    events += ProtocolEvent.PlaylistChanged(files, change.string("user"))
                }
                "playlistIndex" -> {
                    val change = value.asJsonObject
                    change.int("index")?.let {
                        events += ProtocolEvent.PlaylistIndexChanged(it, change.string("user"))
                    }
                }
                "features" -> {
                    val update = value.asJsonObject
                    val username = update.string("username")
                    val features = update.obj("features")
                    if (username != null && features != null) {
                        events += ProtocolEvent.FeaturesChanged(username, features.toMap())
                    }
                }
                "controllerAuth" -> {
                    val auth = value.asJsonObject
                    val success = auth.bool("success") ?: false
                    val user = auth.string("user") ?: "Usuario"
                    events += ProtocolEvent.Notice(
                        if (success) "$user tiene control de la sala" else "No se pudo autenticar a $user como controlador",
                    )
                }
                "newControlledRoom" -> {
                    val room = value.asJsonObject.string("roomName") ?: ""
                    val password = value.asJsonObject.string("password") ?: ""
                    events += ProtocolEvent.Notice("Sala administrada creada: $room · clave: $password")
                }
            }
        }
        return events
    }

    private fun decodeUserDeltas(payload: JsonObject, events: MutableList<ProtocolEvent>) {
        payload.entrySet().forEach { (username, rawSettings) ->
            val settings = rawSettings.asJsonObject
            val event = settings.obj("event")
            if (event?.has("left") == true) {
                events += ProtocolEvent.UserLeft(username)
                return@forEach
            }
            val room = settings.obj("room")?.string("name") ?: ""
            val fileProvided = settings.has("file")
            val readinessProvided = settings.has("isReady")
            val controllerProvided = settings.has("controller")
            val featuresProvided = settings.has("features")
            val user = RoomUser(
                username = username,
                room = room,
                file = settings.obj("file")?.toMediaDescriptor(),
                isReady = settings.bool("isReady"),
                isController = settings.bool("controller") ?: false,
                features = settings.obj("features")?.toMap() ?: emptyMap(),
            )
            events += ProtocolEvent.UserJoinedOrUpdated(
                user = user,
                joined = event?.has("joined") == true,
                fileProvided = fileProvided,
                readinessProvided = readinessProvided,
                controllerProvided = controllerProvided,
                featuresProvided = featuresProvided,
            )
        }
    }

    private fun decodeUserList(payload: JsonObject): List<RoomUser> = buildList {
        payload.entrySet().forEach { (room, rawUsers) ->
            if (!rawUsers.isJsonObject) return@forEach
            rawUsers.asJsonObject.entrySet().forEach { (username, rawUser) ->
                if (username.isBlank() || !rawUser.isJsonObject) return@forEach
                val user = rawUser.asJsonObject
                add(
                    RoomUser(
                        username = username,
                        room = room,
                        file = user.obj("file")?.toMediaDescriptor(),
                        isReady = user.bool("isReady"),
                        isController = user.bool("controller") ?: false,
                        features = user.obj("features")?.toMap() ?: emptyMap(),
                    ),
                )
            }
        }
    }

    private fun decodeState(payload: JsonObject): IncomingStateEnvelope {
        val playstate = payload.obj("playstate")
        val ping = payload.obj("ping")
        val ignoring = payload.obj("ignoringOnTheFly")
        return IncomingStateEnvelope(
            positionSeconds = playstate?.double("position"),
            paused = playstate?.bool("paused"),
            doSeek = playstate?.bool("doSeek") ?: false,
            setBy = playstate?.string("setBy"),
            latencyCalculation = ping?.double("latencyCalculation"),
            echoedClientTimestamp = ping?.double("clientLatencyCalculation"),
            serverRtt = ping?.double("serverRtt"),
            legacyForwardDelaySeconds = ping?.double("yourLatency"),
            serverIgnoringOnTheFly = ignoring?.int("server"),
            clientIgnoringOnTheFly = ignoring?.int("client"),
        )
    }

    private fun decodeChat(payload: JsonElement): ProtocolEvent.Chat? {
        if (!payload.isJsonObject) return null
        val message = payload.asJsonObject
        val username = message.string("username") ?: return null
        val text = message.string("message") ?: return null
        return ProtocolEvent.Chat(username, text)
    }

    private fun command(name: String, value: JsonElement): String = gson.toJson(
        JsonObject().apply { add(name, value) },
    )

    private fun setCommand(name: String, value: JsonElement): String = command(
        "Set",
        JsonObject().apply { add(name, value) },
    )

    private fun JsonObject.toMediaDescriptor(): MediaDescriptor? {
        if (entrySet().isEmpty()) return null
        val name = string("name") ?: return null
        return MediaDescriptor(
            name = name,
            durationSeconds = double("duration") ?: 0.0,
            sizeBytes = long("size") ?: 0L,
        )
    }

    private fun JsonObject.toMap(): Map<String, Any?> = gson.fromJson(this, mapType)

    private fun JsonObject.obj(name: String): JsonObject? = get(name)
        ?.takeUnless { it.isJsonNull }
        ?.takeIf { it.isJsonObject }
        ?.asJsonObject

    private fun JsonObject.string(name: String): String? = get(name)
        ?.takeUnless { it.isJsonNull }
        ?.takeIf { it.isJsonPrimitive }
        ?.asString

    private fun JsonObject.bool(name: String): Boolean? = runCatching {
        get(name)?.takeUnless { it.isJsonNull }?.asBoolean
    }.getOrNull()

    private fun JsonObject.double(name: String): Double? = runCatching {
        get(name)?.takeUnless { it.isJsonNull }?.asDouble
    }.getOrNull()

    private fun JsonObject.long(name: String): Long? = runCatching {
        get(name)?.takeUnless { it.isJsonNull }?.asLong
    }.getOrNull()

    private fun JsonObject.int(name: String): Int? = runCatching {
        get(name)?.takeUnless { it.isJsonNull }?.asInt
    }.getOrNull()

    private fun JsonObject.arrayStrings(name: String): List<String> = get(name)
        ?.takeUnless { it.isJsonNull }
        ?.takeIf { it.isJsonArray }
        ?.asJsonArray
        ?.mapNotNull { element ->
            element.takeUnless { it.isJsonNull }?.takeIf { it.isJsonPrimitive }?.asString
        }
        .orEmpty()

    companion object {
        const val CLIENT_PROTOCOL_VERSION = "1.7.6"

        private const val MAX_JSON_NESTING = 128

        fun md5(value: String): String = MessageDigest.getInstance("MD5")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

/** A remote frame could not be decoded without changing the transport contract. */
internal class ProtocolDecodeException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)
