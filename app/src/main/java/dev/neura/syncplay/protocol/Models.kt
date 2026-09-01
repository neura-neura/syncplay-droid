package dev.neura.syncplay.protocol

/** Settings required by the standard Syncplay TCP protocol. */
data class ConnectionConfig(
    val serverAddress: String = "syncplay.pl:8999",
    val username: String = "",
    val room: String = "",
    val password: String = "",
    val preferTls: Boolean = true,
)

data class ServerEndpoint(
    val host: String,
    val port: Int,
) {
    val displayName: String
        get() = if (host.contains(':')) "[$host]:$port" else "$host:$port"
}

fun parseServerEndpoint(address: String, defaultPort: Int = 8999): ServerEndpoint {
    val value = address.trim()
        .removePrefix("syncplay://")
        .removePrefix("tcp://")
        .trimEnd('/')
    require(value.isNotBlank()) { "Escribe la dirección del servidor" }

    val host: String
    val portText: String?
    if (value.startsWith("[")) {
        val closingBracket = value.indexOf(']')
        require(closingBracket > 1) { "La dirección IPv6 no es válida" }
        host = value.substring(1, closingBracket)
        val remainder = value.substring(closingBracket + 1)
        portText = when {
            remainder.isBlank() -> null
            remainder.startsWith(":") -> remainder.drop(1)
            else -> throw IllegalArgumentException("La dirección del servidor no es válida")
        }
    } else {
        val colonCount = value.count { it == ':' }
        if (colonCount == 1) {
            host = value.substringBeforeLast(':')
            portText = value.substringAfterLast(':')
        } else {
            // An unbracketed IPv6 literal is accepted with the default port.
            host = value
            portText = null
        }
    }

    require(host.isNotBlank()) { "El nombre del servidor está vacío" }
    val port = if (portText == null) {
        defaultPort
    } else {
        require(portText.isNotBlank()) { "Falta el puerto del servidor" }
        portText.toIntOrNull() ?: throw IllegalArgumentException("El puerto no es un número")
    }
    require(port in 1..65535) { "El puerto debe estar entre 1 y 65535" }
    return ServerEndpoint(host, port)
}

/** Change only the port of an existing server address, preserving its host. */
fun serverAddressWithPort(address: String, port: Int): String {
    require(port in 1..65535) { "El puerto debe estar entre 1 y 65535" }
    val endpoint = parseServerEndpoint(address)
    return ServerEndpoint(endpoint.host, port).displayName
}

sealed interface ConnectionStatus {
    data object Disconnected : ConnectionStatus
    data class Connecting(val endpoint: String) : ConnectionStatus
    data class Securing(val endpoint: String) : ConnectionStatus
    data class Reconnecting(val endpoint: String, val attempt: Int, val reason: String) : ConnectionStatus
    data class Connected(
        val endpoint: String,
        val secure: Boolean,
        val serverVersion: String,
    ) : ConnectionStatus
    data class Error(val message: String) : ConnectionStatus
}

data class MediaDescriptor(
    val name: String,
    val durationSeconds: Double,
    val sizeBytes: Long,
)

data class RoomUser(
    val username: String,
    val room: String,
    val file: MediaDescriptor? = null,
    val isReady: Boolean? = null,
    val isController: Boolean = false,
    val features: Map<String, Any?> = emptyMap(),
)

data class ChatEntry(
    val id: Long,
    val username: String?,
    val message: String,
    val timestampMillis: Long = System.currentTimeMillis(),
    val isSystem: Boolean = username == null,
)

data class LocalPlaybackState(
    val positionSeconds: Double,
    val paused: Boolean,
)

data class RemotePlaybackState(
    val positionSeconds: Double,
    val paused: Boolean,
    val doSeek: Boolean,
    val setBy: String?,
    val messageAgeSeconds: Double,
)

data class ServerHello(
    val username: String,
    val room: String,
    val version: String,
    val motd: String?,
    val features: Map<String, Any?>,
)

/** Syncplay feature negotiation with the same advertised-flag/version fallback used on the wire. */
internal fun supportsSyncplayFeature(
    features: Map<String, Any?>,
    serverVersion: String?,
    feature: String,
    minimumVersion: String,
): Boolean {
    val advertised = features[feature]
    if (advertised is Boolean) return advertised
    return syncplayVersionAtLeast(serverVersion, minimumVersion)
}

internal fun syncplayVersionAtLeast(actual: String?, minimum: String): Boolean {
    val left = actual.orEmpty().split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    val right = minimum.split('.').map { it.toIntOrNull() ?: 0 }
    repeat(maxOf(left.size, right.size)) { index ->
        val comparison = left.getOrElse(index) { 0 }.compareTo(right.getOrElse(index) { 0 })
        if (comparison != 0) return comparison > 0
    }
    return true
}

sealed interface ProtocolEvent {
    data class Hello(val value: ServerHello) : ProtocolEvent
    data class UserList(val users: List<RoomUser>) : ProtocolEvent
    data class UserJoinedOrUpdated(
        val user: RoomUser,
        val joined: Boolean = false,
        val fileProvided: Boolean = false,
        val readinessProvided: Boolean = false,
        val controllerProvided: Boolean = false,
        val featuresProvided: Boolean = false,
    ) : ProtocolEvent
    data class UserLeft(val username: String) : ProtocolEvent
    data class RoomChanged(val room: String) : ProtocolEvent
    data class ReadyChanged(
        val username: String,
        val isReady: Boolean,
        val manuallyInitiated: Boolean,
        val setBy: String?,
    ) : ProtocolEvent
    data class Chat(val username: String, val message: String) : ProtocolEvent
    data class Playback(val value: RemotePlaybackState) : ProtocolEvent
    data class PlaylistChanged(val files: List<String>, val setBy: String?) : ProtocolEvent
    data class PlaylistIndexChanged(val index: Int, val setBy: String?) : ProtocolEvent
    data class FeaturesChanged(val username: String, val features: Map<String, Any?>) : ProtocolEvent
    data class Notice(val message: String) : ProtocolEvent
    data class Error(val message: String) : ProtocolEvent
}
