package dev.neura.syncplay.protocol

import android.os.Build
import java.io.BufferedInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.InetAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.security.cert.X509Certificate
import java.net.IDN
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.math.min

/**
 * Standard Syncplay client transport: UTF-8 JSON objects delimited by CRLF over TCP, optionally
 * upgraded in-place with Syncplay's STARTTLS exchange. It deliberately does not use WebSockets.
 */
class SyncplayConnection(
    private val codec: SyncplayProtocolCodec = SyncplayProtocolCodec(),
    private val pingTracker: PingTracker = PingTracker(),
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
    val status: StateFlow<ConnectionStatus> = _status.asStateFlow()

    private val _events = MutableSharedFlow<ProtocolEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<ProtocolEvent> = _events.asSharedFlow()

    private val writerLock = Any()
    private val sessionStateLock = Any()
    private val protocolStateLock = Any()
    private val sessionSequence = AtomicLong(0L)
    private val attemptSequence = AtomicLong(0L)
    private val helloSequence = AtomicLong(0L)
    // A slow/non-reading peer must not turn periodic State replies into an unbounded heap queue.
    // When full, trySend returns false; transient state acknowledgements may be retried on the
    // next server tick while user-facing senders can keep their draft/action unchanged.
    private val outbound = Channel<Outbound>(capacity = OUTBOUND_QUEUE_CAPACITY)

    @Volatile private var activeGeneration = 0L
    @Volatile private var activeAttempt = 0L
    @Volatile private var transport: Transport? = null
    @Volatile private var connectionJob: Job? = null
    @Volatile private var disconnectRequested = false
    @Volatile private var logged = false
    @Volatile private var localStateProvider: (() -> LocalPlaybackState?)? = null
    @Volatile private var remoteStateApplier:
        (suspend (RemotePlaybackState) -> LocalPlaybackState?)? = null
    @Volatile private var currentConfig: ConnectionConfig? = null
    @Volatile private var negotiatedVersion: String? = null
    @Volatile private var negotiatedFeatures: Map<String, Any?> = emptyMap()
    @Volatile private var controllerPassword: String? = null

    private var clientIgnoringOnTheFly = 0
    private var serverIgnoringOnTheFly = 0

    init {
        scope.launch {
            for (message in outbound) {
                if (isCurrentAttempt(message.generation, message.attempt)) writeLineNow(message)
            }
        }
    }

    fun setLocalStateProvider(provider: () -> LocalPlaybackState?) {
        localStateProvider = provider
    }

    fun setRemoteStateApplier(applier: suspend (RemotePlaybackState) -> LocalPlaybackState?) {
        remoteStateApplier = applier
    }

    fun connect(config: ConnectionConfig) {
        require(config.username.isNotBlank()) { "Escribe un nombre de usuario" }
        require(config.room.isNotBlank()) { "Escribe el nombre de la sala" }
        val endpoint = parseServerEndpoint(config.serverAddress)
        val roomCredentials = parseRoomCredentials(config.room)
        val normalized = config.copy(
            serverAddress = endpoint.displayName,
            username = config.username.trim(),
            room = roomCredentials.room,
        )
        val (previousGeneration, generation) = synchronized(sessionStateLock) {
            val previous = activeGeneration
            val next = sessionSequence.incrementAndGet()
            activeGeneration = next
            activeAttempt = attemptSequence.incrementAndGet()
            disconnectRequested = false
            logged = false
            currentConfig = normalized
            controllerPassword = roomCredentials.password
            negotiatedVersion = null
            negotiatedFeatures = emptyMap()
            previous to next
        }
        connectionJob?.cancel()
        closeTransport(previousGeneration)
        resetProtocolState()
        connectionJob = scope.launch { connectionLoop(normalized, endpoint, generation) }
    }

    fun disconnect() {
        val previousGeneration = synchronized(sessionStateLock) {
            disconnectRequested = true
            logged = false
            activeGeneration.also {
                activeGeneration = sessionSequence.incrementAndGet()
                activeAttempt = attemptSequence.incrementAndGet()
                negotiatedVersion = null
                negotiatedFeatures = emptyMap()
                _status.value = ConnectionStatus.Disconnected
            }
        }
        connectionJob?.cancel()
        connectionJob = null
        closeTransport(previousGeneration)
        resetProtocolState()
    }

    fun sendPlaybackChange(state: LocalPlaybackState, doSeek: Boolean) {
        val session = synchronized(sessionStateLock) {
            if (logged) activeGeneration to activeAttempt else null
        } ?: return
        val line = synchronized(protocolStateLock) {
            clientIgnoringOnTheFly += 1
            buildStateLine(
                local = state,
                latencyCalculation = null,
                doSeek = doSeek,
                forcePlaystate = true,
            )
        }
        enqueue(session.first, session.second, line)
    }

    fun sendFile(file: MediaDescriptor) = send(codec.setFile(file))

    fun sendReady(isReady: Boolean, manuallyInitiated: Boolean = true): Boolean =
        supports("readiness", "1.3.0") && send(codec.setReady(isReady, manuallyInitiated))

    fun requestUserList() = send(codec.requestList())

    fun sendChat(message: String): Boolean {
        val trimmed = message.trim()
        if (trimmed.isEmpty() || !supports("chat", "1.5.0")) return false
        return send(codec.chat(trimmed))
    }

    fun changeRoom(room: String, controllerPassword: String? = null) {
        val parsed = parseRoomCredentials(room)
        val normalized = parsed.room
        if (normalized.isEmpty()) return
        val password = controllerPassword?.takeIf { it.isNotBlank() } ?: parsed.password
        synchronized(sessionStateLock) {
            currentConfig = currentConfig?.copy(room = normalized)
            this.controllerPassword = password
        }
        send(codec.setRoom(normalized))
        if (password != null && supports("managedRooms", "1.3.0")) {
            send(codec.authenticateController(normalized, password))
        }
        requestUserList()
    }

    private suspend fun connectionLoop(
        initialConfig: ConnectionConfig,
        endpoint: ServerEndpoint,
        generation: Long,
    ) {
        var reconnectAttempt = 0
        var hadSuccessfulHello = false
        while (scope.isActive && isCurrent(generation)) {
            val attempt = synchronized(sessionStateLock) {
                if (!isCurrent(generation)) return
                attemptSequence.incrementAndGet().also {
                    activeAttempt = it
                    logged = false
                    negotiatedVersion = null
                    negotiatedFeatures = emptyMap()
                }
            }
            val helloBeforeAttempt = helloSequence.get()
            try {
                if (reconnectAttempt == 0) {
                    mutateIfCurrent(generation, attempt) {
                        _status.value = ConnectionStatus.Connecting(endpoint.displayName)
                    }
                }
                val desired = currentConfig?.takeIf { isCurrent(generation) } ?: initialConfig
                runConnection(desired, endpoint, generation, attempt)
                if (!isCurrent(generation)) return
                throw EOFException("El servidor cerró la conexión")
            } catch (unsupported: TlsUnsupportedException) {
                closeTransport(generation, attempt)
                mutateIfCurrent(generation, attempt) {
                    logged = false
                    _status.value = ConnectionStatus.Error(
                        unsupported.message ?: "El servidor no admite TLS",
                    )
                }
                return
            } catch (certificate: SSLHandshakeException) {
                mutateIfCurrent(generation, attempt) {
                    logged = false
                    _status.value = ConnectionStatus.Error(
                        "El certificado TLS del servidor no es válido: ${certificate.message.orEmpty()}",
                    )
                }
                closeTransport(generation, attempt)
                return
            } catch (fatal: FatalProtocolException) {
                mutateIfCurrent(generation, attempt) {
                    logged = false
                    _status.value = ConnectionStatus.Error(fatal.message ?: "El servidor rechazó la conexión")
                }
                closeTransport(generation, attempt)
                return
            } catch (cancelled: CancellationException) {
                closeTransport(generation, attempt)
                throw cancelled
            } catch (error: Exception) {
                if (!mutateIfCurrent(generation, attempt) { logged = false }) return
                val gotHelloThisAttempt = helloSequence.get() != helloBeforeAttempt
                hadSuccessfulHello = hadSuccessfulHello || gotHelloThisAttempt
                closeTransport(generation, attempt)
                val reason = friendlyError(error)
                reconnectAttempt = if (gotHelloThisAttempt) 1 else reconnectAttempt + 1
                if (!hadSuccessfulHello && reconnectAttempt > INITIAL_RETRIES) {
                    mutateIfCurrent(generation, attempt) { _status.value = ConnectionStatus.Error(reason) }
                    return
                }
                if (!mutateIfCurrent(generation, attempt) {
                        _status.value = ConnectionStatus.Reconnecting(endpoint.displayName, reconnectAttempt, reason)
                    }
                ) return
                delay(min(15_000L, 1_000L shl min(4, reconnectAttempt - 1)))
            }
        }
    }

    private suspend fun runConnection(
        config: ConnectionConfig,
        endpoint: ServerEndpoint,
        generation: Long,
        attempt: Long,
    ) {
        val stateReset = synchronized(sessionStateLock) {
            if (!isCurrentAttempt(generation, attempt)) return@synchronized false
            synchronized(protocolStateLock) {
                pingTracker.reset()
                clientIgnoringOnTheFly = 0
                serverIgnoringOnTheFly = 0
            }
            true
        }
        if (!stateReset) return

        var socket: Socket = Socket()
        try {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.connect(InetSocketAddress(endpoint.host, endpoint.port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            registerTransport(generation, attempt, socket, writer = null)

            var reader = LimitedLineReader(socket.getInputStream(), MAX_LINE_BYTES)
            var directWriter = socket.writer()
            var secure = false

            if (config.preferTls) {
                if (!mutateIfCurrent(generation, attempt) {
                        _status.value = ConnectionStatus.Securing(endpoint.displayName)
                    }
                ) return
                directWriter.writeFramed(codec.tlsRequest())
                val response = reader.readLine()
                    ?: throw EOFException("El servidor cerró la conexión durante STARTTLS")
                when {
                    codec.isTlsAccepted(response) -> {
                        val sslSocket = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                            .createSocket(socket, endpoint.host, endpoint.port, true) as SSLSocket
                        sslSocket.useClientMode = true
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            sslSocket.sslParameters = sslSocket.sslParameters.apply {
                                endpointIdentificationAlgorithm = "HTTPS"
                            }
                        }
                        sslSocket.startHandshake()
                        val certificate = sslSocket.session.peerCertificates.firstOrNull() as? X509Certificate
                        if (certificate == null || !certificateMatchesHost(endpoint.host, certificate)) {
                            throw SSLHandshakeException("El certificado no pertenece a ${endpoint.host}")
                        }
                        socket = sslSocket
                        registerTransport(generation, attempt, socket, writer = null)
                        reader = LimitedLineReader(socket.getInputStream(), MAX_LINE_BYTES)
                        directWriter = socket.writer()
                        secure = true
                    }
                    codec.isTlsDeclined(response) -> throw TlsUnsupportedException(
                        "El servidor no ofrece TLS. Desactiva «Exigir TLS» sólo si confías en la red.",
                    )
                    else -> {
                        val legacyError = runCatching { codec.decode(response).events }
                            .getOrDefault(emptyList())
                            .filterIsInstance<ProtocolEvent.Error>()
                            .firstOrNull()
                        val message = legacyError?.message.orEmpty()
                        if (message.contains("starttls", ignoreCase = true) ||
                            message.contains("unknown command", ignoreCase = true)
                        ) {
                            throw TlsUnsupportedException(
                                "El servidor no reconoce STARTTLS. Desactiva «Exigir TLS» sólo si confías en la red.",
                            )
                        }
                        throw FatalProtocolException("Respuesta STARTTLS no válida")
                    }
                }
            }

            socket.soTimeout = PROTOCOL_TIMEOUT_MS
            directWriter.writeFramed(codec.hello(config))
            // Publish the writer only after Hello is on the wire. This prevents a queued frame
            // from a reconnecting client from becoming the first command on a fresh socket.
            registerTransport(generation, attempt, socket, directWriter)
            var lastStateAtNanos = System.nanoTime()

            while (scope.isActive && isCurrentAttempt(generation, attempt)) {
                val line = try {
                    reader.readLine()
                } catch (timeout: SocketTimeoutException) {
                    throw SocketTimeoutException("No se recibió estado del servidor durante 13 segundos")
                } ?: throw EOFException("El servidor cerró la conexión")
                if (!isCurrentAttempt(generation, attempt)) return
                if (line.isBlank()) continue
                val decoded = codec.decode(line)
                decoded.events.forEach { event ->
                    if (!isCurrentAttempt(generation, attempt)) return
                    when (event) {
                        is ProtocolEvent.Hello -> {
                            if (!mutateIfCurrent(generation, attempt) {
                                    logged = true
                                    negotiatedVersion = event.value.version
                                    negotiatedFeatures = event.value.features
                                    helloSequence.incrementAndGet()
                                    _status.value = ConnectionStatus.Connected(
                                        endpoint = endpoint.displayName,
                                        secure = secure,
                                        serverVersion = event.value.version,
                                    )
                                }
                            ) return
                            controllerPassword?.let { password ->
                                if (supports("managedRooms", "1.3.0")) {
                                    enqueue(
                                        generation,
                                        attempt,
                                        codec.authenticateController(config.room, password),
                                    )
                                }
                            }
                        }
                        is ProtocolEvent.Error -> {
                            _events.emit(event)
                            throw FatalProtocolException(event.message)
                        }
                        else -> Unit
                    }
                    _events.emit(event)
                }
                decoded.state?.let {
                    lastStateAtNanos = System.nanoTime()
                    handleIncomingState(it, generation, attempt)
                }
                if (System.nanoTime() - lastStateAtNanos > PROTOCOL_TIMEOUT_NANOS) {
                    throw SocketTimeoutException("No se recibió estado del servidor durante 13 segundos")
                }
            }
        } finally {
            runCatching { socket.close() }
            clearTransport(generation, attempt, socket)
        }
    }

    private suspend fun handleIncomingState(
        incoming: IncomingStateEnvelope,
        generation: Long,
        attempt: Long,
    ) {
        if (!isCurrentAttempt(generation, attempt)) return
        val event: ProtocolEvent.Playback?
        synchronized(protocolStateLock) {
            pingTracker.receive(incoming.echoedClientTimestamp, incoming.serverRtt)
            incoming.serverIgnoringOnTheFly?.let {
                serverIgnoringOnTheFly = it
                clientIgnoringOnTheFly = 0
            } ?: incoming.clientIgnoringOnTheFly?.let {
                if (it == clientIgnoringOnTheFly) clientIgnoringOnTheFly = 0
            }

            event = if (
                clientIgnoringOnTheFly == 0 &&
                incoming.positionSeconds != null &&
                incoming.paused != null
            ) {
                ProtocolEvent.Playback(
                    RemotePlaybackState(
                        positionSeconds = incoming.positionSeconds,
                        paused = incoming.paused,
                        doSeek = incoming.doSeek,
                        setBy = incoming.setBy,
                        messageAgeSeconds = pingTracker.forwardDelaySeconds
                            .takeIf { it > 0.0 }
                            ?: incoming.legacyForwardDelaySeconds
                            ?: 0.0,
                    ),
                )
            } else null

        }

        var deliveredByApplier = false
        val responseState = event?.value?.let { remote ->
            val applier = remoteStateApplier
            if (applier != null) {
                try {
                    val applied = applier(remote)
                    deliveredByApplier = true
                    applied
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    localStateProvider?.invoke()
                }
            } else {
                // Non-Android/test consumers keep the asynchronous event API. Never claim a
                // remote position when there is no local media snapshot to apply it to.
                localStateProvider?.invoke()?.let {
                    LocalPlaybackState(
                        positionSeconds = remote.positionSeconds +
                            if (remote.paused) 0.0 else remote.messageAgeSeconds.coerceIn(0.0, 5.0),
                        paused = remote.paused,
                    )
                }
            }
        } ?: localStateProvider?.invoke()
        if (!isCurrentAttempt(generation, attempt)) return
        val response = synchronized(protocolStateLock) {
            buildStateLine(
                local = responseState,
                latencyCalculation = incoming.latencyCalculation,
                doSeek = false,
            )
        }
        // The Android applier runs on the Player looper before this acknowledgement, matching
        // desktop Syncplay's apply-then-read-local-state ordering.
        enqueue(generation, attempt, response)
        if (!deliveredByApplier && isCurrentAttempt(generation, attempt)) {
            event?.let { _events.emit(it) }
        }
    }

    private fun buildStateLine(
        local: LocalPlaybackState?,
        latencyCalculation: Double?,
        doSeek: Boolean,
        forcePlaystate: Boolean = false,
    ): String {
        val serverIgnore = serverIgnoringOnTheFly
        val line = codec.state(
            local = local,
            latencyCalculation = latencyCalculation,
            clientRtt = pingTracker.rttSeconds,
            clientTimestamp = pingTracker.timestamp(),
            clientIgnoringOnTheFly = clientIgnoringOnTheFly,
            serverIgnoringOnTheFly = serverIgnore,
            doSeek = doSeek,
            forcePlaystate = forcePlaystate,
        )
        if (serverIgnore != 0) serverIgnoringOnTheFly = 0
        return line
    }

    private fun supports(feature: String, minimumVersion: String): Boolean {
        return synchronized(sessionStateLock) {
            if (!logged) return@synchronized false
            supportsSyncplayFeature(negotiatedFeatures, negotiatedVersion, feature, minimumVersion)
        }
    }

    private fun certificateMatchesHost(host: String, certificate: X509Certificate): Boolean {
        val normalizedHost = normalizeHostname(host)
        val isIpLiteral = normalizedHost.contains(':') || IPV4.matches(normalizedHost)
        val wantedType = if (isIpLiteral) 7 else 2
        val subjectAlternativeNames = runCatching { certificate.subjectAlternativeNames.orEmpty() }
            .getOrDefault(emptyList())
        val alternatives = subjectAlternativeNames
            .mapNotNull { entry ->
                val type = entry.getOrNull(0) as? Int ?: return@mapNotNull null
                val value = entry.getOrNull(1)?.toString() ?: return@mapNotNull null
                if (type == wantedType) value else null
            }
        if (subjectAlternativeNames.isNotEmpty()) {
            return alternatives.any { alternative ->
                if (isIpLiteral) ipAddressesEqual(normalizedHost, alternative) else dnsNameMatches(normalizedHost, alternative)
            }
        }
        if (isIpLiteral) return false
        val commonName = certificate.subjectX500Principal.name
            .split(',')
            .firstOrNull { it.trim().startsWith("CN=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim()
            ?: return false
        return dnsNameMatches(normalizedHost, commonName)
    }

    private fun dnsNameMatches(host: String, pattern: String): Boolean {
        val normalizedPattern = normalizeHostname(pattern)
        if (!normalizedPattern.contains('*')) return host == normalizedPattern
        if (!normalizedPattern.startsWith("*.") || normalizedPattern.count { it == '*' } != 1) return false
        val suffix = normalizedPattern.drop(1)
        return host.endsWith(suffix) && host.count { it == '.' } == normalizedPattern.count { it == '.' }
    }

    private fun normalizeHostname(value: String): String = runCatching {
        IDN.toASCII(value.trim().trimEnd('.'), IDN.USE_STD3_ASCII_RULES).lowercase()
    }.getOrDefault(value.trim().trimEnd('.').lowercase())

    private fun ipAddressesEqual(left: String, right: String): Boolean = runCatching {
        InetAddress.getByName(left).address.contentEquals(InetAddress.getByName(right).address)
    }.getOrDefault(false)

    private fun parseRoomCredentials(value: String): RoomCredentials {
        val room = value.trim()
        if (!room.startsWith('+') || room.count { it == ':' } < 2) return RoomCredentials(room, null)
        val password = room.substringAfterLast(':').takeIf { it.isNotBlank() }
        val roomWithoutPassword = room.substringBeforeLast(':').takeIf { it.isNotBlank() } ?: room
        return RoomCredentials(roomWithoutPassword, password)
    }

    private fun send(line: String): Boolean {
        val session = synchronized(sessionStateLock) {
            if (logged) activeGeneration to activeAttempt else null
        } ?: return false
        return enqueue(session.first, session.second, line)
    }

    private fun enqueue(generation: Long, attempt: Long, line: String): Boolean =
        isCurrentAttempt(generation, attempt) &&
            outbound.trySend(Outbound(generation, attempt, line)).isSuccess

    private fun writeLineNow(message: Outbound) {
        val active = synchronized(writerLock) {
            transport?.takeIf {
                it.generation == message.generation && it.attempt == message.attempt
            }
        } ?: return
        val activeWriter = active.writer ?: return
        try {
            // Do not hold writerLock during blocking I/O. disconnect() can then close the socket
            // immediately, which also unblocks a peer that stopped reading.
            activeWriter.writeFramed(message.line)
        } catch (_: Exception) {
            runCatching { active.socket.close() }
        }
    }

    private fun BufferedWriter.writeFramed(line: String) {
        write(line)
        write("\r\n")
        flush()
    }

    private fun Socket.writer() = BufferedWriter(OutputStreamWriter(getOutputStream(), StandardCharsets.UTF_8))

    private fun registerTransport(
        generation: Long,
        attempt: Long,
        socket: Socket,
        writer: BufferedWriter?,
    ) {
        if (!isCurrentAttempt(generation, attempt)) {
            runCatching { socket.close() }
            throw CancellationException("Conexión reemplazada")
        }
        synchronized(writerLock) {
            if (!isCurrentAttempt(generation, attempt)) {
                runCatching { socket.close() }
                throw CancellationException("Conexión reemplazada")
            }
            transport = Transport(generation, attempt, socket, writer)
        }
    }

    private fun closeTransport(generation: Long, attempt: Long? = null) {
        synchronized(writerLock) {
            val active = transport?.takeIf {
                it.generation == generation && (attempt == null || it.attempt == attempt)
            } ?: return
            transport = null
            runCatching { active.socket.close() }
        }
    }

    private fun clearTransport(generation: Long, attempt: Long, socket: Socket) {
        synchronized(writerLock) {
            val active = transport
            if (active?.generation == generation && active.attempt == attempt && active.socket === socket) {
                transport = null
            }
        }
    }

    private fun resetProtocolState() {
        synchronized(protocolStateLock) {
            clientIgnoringOnTheFly = 0
            serverIgnoringOnTheFly = 0
            pingTracker.reset()
        }
    }

    private fun isCurrent(generation: Long): Boolean =
        generation == activeGeneration && !disconnectRequested

    private fun isCurrentAttempt(generation: Long, attempt: Long): Boolean =
        isCurrent(generation) && attempt == activeAttempt

    private fun mutateIfCurrent(
        generation: Long,
        attempt: Long,
        mutation: () -> Unit,
    ): Boolean = synchronized(sessionStateLock) {
        if (!isCurrentAttempt(generation, attempt)) return@synchronized false
        mutation()
        true
    }

    private fun friendlyError(error: Exception): String = when (error) {
        is java.net.UnknownHostException -> "No se encontró el servidor"
        is java.net.ConnectException -> "No se pudo conectar al servidor"
        is SocketTimeoutException -> error.message ?: "La conexión agotó el tiempo de espera"
        is SocketException -> error.message ?: "Se perdió la conexión con el servidor"
        is LineTooLongException -> error.message ?: "El servidor envió un mensaje demasiado grande"
        else -> error.message ?: error.javaClass.simpleName
    }

    override fun close() {
        disconnect()
        outbound.close()
        scope.cancel()
    }

    private data class Transport(
        val generation: Long,
        val attempt: Long,
        val socket: Socket,
        val writer: BufferedWriter?,
    )

    private data class Outbound(val generation: Long, val attempt: Long, val line: String)
    private data class RoomCredentials(val room: String, val password: String?)
    private class FatalProtocolException(message: String) : Exception(message)
    private class TlsUnsupportedException(message: String) : Exception(message)
    private class LineTooLongException(message: String) : Exception(message)

    internal class LimitedLineReader(
        input: InputStream,
        private val maximumBytes: Int,
    ) {
        // Keep the existing byte limit and CRLF framing, but avoid issuing one socket read for
        // every byte.  A buffered wrapper only changes the transport read granularity; callers
        // still receive one decoded line at a time and the socket's read timeout remains intact.
        private val input = BufferedInputStream(input, READ_BUFFER_BYTES)

        fun readLine(): String? {
            val bytes = ByteArrayOutputStream(INITIAL_CAPACITY)
            while (true) {
                val value = input.read()
                if (value == -1) {
                    if (bytes.size() == 0) return null
                    break
                }
                if (value == '\n'.code) break
                if (bytes.size() >= maximumBytes) {
                    throw LineTooLongException("La línea supera $maximumBytes bytes")
                }
                bytes.write(value)
            }
            val data = bytes.toByteArray()
            val length = if (data.lastOrNull() == '\r'.code.toByte()) data.size - 1 else data.size
            return String(data, 0, length.coerceAtLeast(0), StandardCharsets.UTF_8)
        }

        private companion object {
            const val INITIAL_CAPACITY = 256
            const val READ_BUFFER_BYTES = 8 * 1024
        }
    }

    private companion object {
        const val OUTBOUND_QUEUE_CAPACITY = 256
        const val CONNECT_TIMEOUT_MS = 10_000
        const val HANDSHAKE_TIMEOUT_MS = 10_000
        const val PROTOCOL_TIMEOUT_MS = 13_000
        const val PROTOCOL_TIMEOUT_NANOS = PROTOCOL_TIMEOUT_MS * 1_000_000L
        const val MAX_LINE_BYTES = 65_536
        const val INITIAL_RETRIES = 3
        val IPV4 = Regex("^(?:\\d{1,3}\\.){3}\\d{1,3}$")
    }
}
