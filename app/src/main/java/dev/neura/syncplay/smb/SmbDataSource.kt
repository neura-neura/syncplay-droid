package dev.neura.syncplay.smb

import android.net.Uri
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileStandardInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File as SmbFile
import java.io.BufferedReader
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.EnumSet
import java.util.LinkedHashMap
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean

/** Pure sizing rules for the bounded sequential read-ahead window. */
internal object SmbReadAheadPolicy {
    fun effectiveCapacity(configuredBytes: Int, maxChunkBytes: Int): Int {
        if (configuredBytes <= 0 || maxChunkBytes <= 0) return 0
        return minOf(configuredBytes, maxChunkBytes)
    }

    fun shouldReadAhead(requestedBytes: Int, capacityBytes: Int): Boolean =
        requestedBytes > 0 && capacityBytes > 0 && requestedBytes < capacityBytes

    /** Size of a window allocation for a source with [remainingBytes] available. */
    fun windowLength(remainingBytes: Long, capacityBytes: Int): Int {
        if (remainingBytes <= 0L || capacityBytes <= 0) return 0
        val remainingInt = if (remainingBytes >= Int.MAX_VALUE.toLong()) {
            Int.MAX_VALUE
        } else {
            remainingBytes.toInt()
        }
        return minOf(remainingInt, capacityBytes)
    }

    fun prefetchLength(remainingBytes: Long, capacityBytes: Int, maxChunkBytes: Int): Int {
        val boundedCapacity = effectiveCapacity(capacityBytes, maxChunkBytes)
        return windowLength(remainingBytes, boundedCapacity)
    }
}

/**
 * A seekable MPV source backed by one process-local SMB lease.
 *
 * [location] is deliberately a loopback URL containing only an opaque token. The SMB host,
 * share, path, username, and password never leave this process. Call [close] when MPV no longer
 * needs the source; closing the lease shuts down the loopback server and SMB connection.
 */
data class SmbMpvSource(
    val location: String,
    val length: Long,
    private val lease: Closeable,
) : Closeable {
    private val closed = AtomicBoolean(false)
    internal var onClosed: ((SmbMpvSource) -> Unit)? = null

    /** Alias used by callers that name the value as a byte size. */
    val sizeBytes: Long
        get() = length

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            lease.close()
        } finally {
            onClosed?.invoke(this)
            onClosed = null
        }
    }
}

/** Open an SMB file as a bounded random-access reader and expose it through a loopback URL. */
internal fun openSmbMpvSource(
    uri: Uri,
    profiles: SmbConnectionProfileRegistry,
): SmbMpvSource {
    val location = try {
        SmbUri.parse(uri)
    } catch (error: IllegalArgumentException) {
        throw IOException("Invalid SMB media URI", error)
    }
    val profile = profiles.get(location.profileId)
        ?: throw IOException("Unknown SMB connection profile")
    val handle = SmbFileHandle(profile, location)
    return try {
        val length = handle.open()
        val reader = SmbRandomAccessReader(handle, length)
        val server = SmbLoopbackRangeServer(reader)
        try {
            server.start()
            SmbMpvSource(
                location = server.location,
                length = length,
                lease = SmbSourceLease(reader, server),
            )
        } catch (error: Exception) {
            server.close()
            reader.close()
            throw if (error is IOException) error else IOException("Unable to start SMB source", error)
        }
    } catch (error: Exception) {
        handle.close()
        throw if (error is IOException) error else IOException("Unable to open SMB media", error)
    }
}

/** Open an SMB URI as a sequential stream. The stream owns and closes its SMB lease. */
internal fun openSmbInputStream(
    uri: Uri,
    profiles: SmbConnectionProfileRegistry,
): InputStream {
    val location = try {
        SmbUri.parse(uri)
    } catch (error: IllegalArgumentException) {
        throw IOException("Invalid SMB media URI", error)
    }
    val profile = profiles.get(location.profileId)
        ?: throw IOException("Unknown SMB connection profile")
    val handle = SmbFileHandle(profile, location)
    return try {
        val length = handle.open()
        SmbSequentialInputStream(SmbRandomAccessReader(handle, length))
    } catch (error: Exception) {
        handle.close()
        throw if (error is IOException) error else IOException("Unable to open SMB stream", error)
    }
}

/** A byte interval with inclusive endpoints, suitable for a single HTTP Range request. */
internal data class SmbByteRange(
    val start: Long,
    val endInclusive: Long,
) {
    init {
        require(start >= 0L) { "Range start must not be negative" }
        require(endInclusive >= start) { "Range end must not precede start" }
    }

    val length: Long
        get() = endInclusive - start + 1L
}

/** Result of parsing one RFC 7233 byte-range header. */
internal sealed class SmbRangeResult {
    data object Absent : SmbRangeResult()
    data class Satisfied(val range: SmbByteRange) : SmbRangeResult()
    data object Unsatisfiable : SmbRangeResult()
    data object Malformed : SmbRangeResult()
}

/** Deterministic HTTP response metadata for a parsed range request. */
internal data class SmbRangeResponse(
    val status: Int,
    val contentLength: Long,
    val contentRange: String? = null,
)

/** Pure Range parsing and response policy, kept independent of sockets for deterministic tests. */
internal object SmbRangeProtocol {
    fun parseRangeHeader(header: String?, resourceLength: Long): SmbRangeResult {
        require(resourceLength >= 0L) { "Resource length must not be negative" }
        if (header.isNullOrBlank()) return SmbRangeResult.Absent
        val value = header.trim()
        if (!value.regionMatches(0, "bytes=", 0, 6, ignoreCase = true)) {
            return SmbRangeResult.Malformed
        }
        val spec = value.substring(6).trim()
        // The bridge intentionally supports one range only. A caller can issue another request
        // for a different interval, while rejecting multipart responses keeps the implementation
        // deterministic and avoids accidental unbounded buffering.
        if (spec.isEmpty() || spec.indexOf(',') >= 0 || spec.indexOf('-') < 0) {
            return SmbRangeResult.Malformed
        }
        val dash = spec.indexOf('-')
        if (dash != spec.lastIndexOf('-')) return SmbRangeResult.Malformed
        val first = spec.substring(0, dash).trim()
        val second = spec.substring(dash + 1).trim()
        if (first.isEmpty()) {
            val suffixLength = second.toLongOrNull() ?: return SmbRangeResult.Malformed
            if (suffixLength <= 0L || resourceLength == 0L) return SmbRangeResult.Unsatisfiable
            val start = (resourceLength - suffixLength).coerceAtLeast(0L)
            return SmbRangeResult.Satisfied(SmbByteRange(start, resourceLength - 1L))
        }
        val start = first.toLongOrNull() ?: return SmbRangeResult.Malformed
        if (start < 0L || start >= resourceLength) return SmbRangeResult.Unsatisfiable
        val requestedEnd = if (second.isEmpty()) {
            resourceLength - 1L
        } else {
            second.toLongOrNull() ?: return SmbRangeResult.Malformed
        }
        if (requestedEnd < start) return SmbRangeResult.Unsatisfiable
        return SmbRangeResult.Satisfied(
            SmbByteRange(start, requestedEnd.coerceAtMost(resourceLength - 1L)),
        )
    }

    fun responsePolicy(result: SmbRangeResult, resourceLength: Long): SmbRangeResponse {
        require(resourceLength >= 0L) { "Resource length must not be negative" }
        return when (result) {
            SmbRangeResult.Absent -> SmbRangeResponse(200, resourceLength)
            is SmbRangeResult.Satisfied -> SmbRangeResponse(
                status = 206,
                contentLength = result.range.length,
                contentRange = "bytes ${result.range.start}-${result.range.endInclusive}/$resourceLength",
            )
            SmbRangeResult.Unsatisfiable,
            SmbRangeResult.Malformed,
            -> SmbRangeResponse(
                status = 416,
                contentLength = 0L,
                contentRange = "bytes */$resourceLength",
            )
        }
    }

    fun statusLine(status: Int): String = when (status) {
        200 -> "200 OK"
        206 -> "206 Partial Content"
        400 -> "400 Bad Request"
        404 -> "404 Not Found"
        405 -> "405 Method Not Allowed"
        416 -> "416 Range Not Satisfiable"
        else -> "$status"
    }
}

/**
 * A random-access reader over one SMB file. Every network request is bounded by [maxChunkBytes]
 * and uses SMBJ's positional read API, so seeks never drain bytes from offset zero. A failed
 * request gets exactly one reconnect-and-retry; close() prevents reconnecting a cancelled read.
 */
internal class SmbRandomAccessReader(
    private val handle: SmbFileHandle,
    val length: Long,
    private val maxChunkBytes: Int = DEFAULT_MAX_READ_CHUNK_BYTES,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val ioLock = Any()

    init {
        require(maxChunkBytes in MIN_READ_CHUNK_BYTES..MAX_READ_CHUNK_BYTES) {
            "SMB read chunk must be between $MIN_READ_CHUNK_BYTES and $MAX_READ_CHUNK_BYTES bytes"
        }
        require(length >= 0L) { "SMB file length must not be negative" }
    }

    fun readAt(buffer: ByteArray, offset: Int, requestedLength: Int, position: Long): Int {
        if (offset < 0 || requestedLength < 0 || offset > buffer.size - requestedLength) {
            throw IndexOutOfBoundsException("Invalid SMB read buffer range")
        }
        if (requestedLength == 0) return 0
        if (position < 0L) throw IOException("SMB read position must not be negative")
        if (position >= length) return -1
        if (closed.get()) throw IOException("SMB reader is closed")
        val available = length - position
        val request = minOf(requestedLength.toLong(), available, maxChunkBytes.toLong()).toInt()
        if (request <= 0) return -1
        synchronized(ioLock) {
            if (closed.get()) throw IOException("SMB reader is closed")
            return try {
                readOnce(buffer, offset, request, position)
            } catch (error: Exception) {
                if (closed.get()) throw IOException("SMB reader is closed", error)
                try {
                    handle.reconnect()
                    readOnce(buffer, offset, request, position)
                } catch (retryError: Exception) {
                    if (closed.get()) throw IOException("SMB reader is closed", retryError)
                    if (retryError is IOException) throw retryError
                    throw IOException("Unable to read SMB media", retryError)
                }
            }
        }
    }

    private fun readOnce(buffer: ByteArray, offset: Int, request: Int, position: Long): Int {
        val count = handle.read(buffer, offset, request, position)
        if (count <= 0) throw IOException("SMB server returned an empty read")
        if (count > request) throw IOException("SMB server returned more bytes than requested")
        return count
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) handle.close()
    }

    companion object {
        /** Default upper bound per SMB READ request (SMBJ further caps to its negotiated size). */
        const val DEFAULT_MAX_READ_CHUNK_BYTES = 8 * 1024 * 1024
        const val MIN_READ_CHUNK_BYTES = 4 * 1024
        const val MAX_READ_CHUNK_BYTES = 8 * 1024 * 1024
    }
}

private class SmbSequentialInputStream(
    private val reader: SmbRandomAccessReader,
    private val readAheadBytes: Int = DEFAULT_READ_AHEAD_BYTES,
) : InputStream() {
    private val closed = AtomicBoolean(false)
    private var position = 0L
    private var window: ByteArray? = null
    private var windowOffset = 0
    private var windowLength = 0

    init {
        require(readAheadBytes in SmbRandomAccessReader.MIN_READ_CHUNK_BYTES..SmbRandomAccessReader.MAX_READ_CHUNK_BYTES)
    }

    override fun read(): Int {
        val one = ByteArray(1)
        val count = read(one, 0, 1)
        return if (count < 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (offset < 0 || length < 0 || offset > buffer.size - length) {
            throw IndexOutOfBoundsException("Invalid SMB stream buffer range")
        }
        if (length == 0) return 0
        if (closed.get()) throw IOException("SMB stream is closed")

        var copied = 0
        val available = windowLength
        if (available > 0) {
            val source = window ?: throw IOException("SMB read-ahead buffer is missing")
            copied = minOf(length, available)
            System.arraycopy(source, windowOffset, buffer, offset, copied)
            windowOffset += copied
            windowLength -= copied
            position += copied
            if (windowLength == 0) windowOffset = 0
            if (copied == length) return copied
        }

        val requested = length - copied
        val capacity = SmbReadAheadPolicy.effectiveCapacity(readAheadBytes, SmbRandomAccessReader.DEFAULT_MAX_READ_CHUNK_BYTES)
        val shouldPrefetch = SmbReadAheadPolicy.shouldReadAhead(requested, capacity)
        if (shouldPrefetch) {
            val target = window ?: ByteArray(capacity).also { window = it }
            val count = reader.readAt(
                target,
                0,
                SmbReadAheadPolicy.prefetchLength(
                    remainingBytes = reader.length - position,
                    capacityBytes = capacity,
                    maxChunkBytes = SmbRandomAccessReader.DEFAULT_MAX_READ_CHUNK_BYTES,
                ),
                position,
            )
            if (count < 0) return if (copied == 0) -1 else copied
            windowOffset = 0
            windowLength = count
            val deliver = minOf(requested, count)
            System.arraycopy(target, 0, buffer, offset + copied, deliver)
            windowOffset = deliver
            windowLength -= deliver
            position += deliver
            return copied + deliver
        }

        val count = reader.readAt(buffer, offset + copied, requested, position)
        if (count < 0) return if (copied == 0) -1 else copied
        position += count
        return copied + count
    }

    override fun skip(byteCount: Long): Long {
        if (byteCount <= 0L) return 0L
        if (closed.get()) throw IOException("SMB stream is closed")
        val skipped = minOf(byteCount, reader.length - position).coerceAtLeast(0L)
        position += skipped
        windowOffset = 0
        windowLength = 0
        return skipped
    }

    override fun available(): Int {
        if (closed.get()) return 0
        return minOf(reader.length - position, Int.MAX_VALUE.toLong()).coerceAtLeast(0L).toInt()
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) reader.close()
    }

    companion object {
        const val DEFAULT_READ_AHEAD_BYTES = 2 * 1024 * 1024
    }
}

private class SmbSourceLease(
    private val reader: SmbRandomAccessReader,
    private val server: SmbLoopbackRangeServer,
) : Closeable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        server.close()
        reader.close()
    }
}

/**
 * Minimal API-23-compatible loopback HTTP server. It intentionally handles only HEAD and GET,
 * one tokenized path, and one byte range per request. A dedicated server per source means closing
 * the source immediately releases both the listening socket and its SMB lease.
 */
private class SmbLoopbackRangeServer(
    private val reader: SmbRandomAccessReader,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val token = UUID.randomUUID().toString().replace("-", "")
    private val socket = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress("127.0.0.1", 0), 16)
    }
    private val workers = Executors.newCachedThreadPool(SmbThreadFactory(token))
    private val acceptThread = Thread({ acceptLoop() }, "smb-http-accept-$token").apply {
        isDaemon = true
    }

    val location: String
        get() = "http://127.0.0.1:${socket.localPort}/$token"

    fun start() {
        acceptThread.start()
    }

    private fun acceptLoop() {
        while (!closed.get()) {
            try {
                val client = socket.accept()
                if (closed.get()) {
                    client.close()
                } else {
                    workers.execute { handle(client) }
                }
            } catch (_: SocketException) {
                if (closed.get()) return
            } catch (_: IOException) {
                if (closed.get()) return
            }
        }
    }

    private fun handle(client: Socket) {
        client.use { socket ->
            runCatching { socket.soTimeout = 15_000 }
            val input = BufferedReader(InputStreamReader(socket.getInputStream(), HTTP_CHARSET))
            val output = socket.getOutputStream()
            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(' ', limit = 3)
            if (parts.size != 3) {
                sendError(output, 400)
                return
            }
            val method = parts[0].uppercase(Locale.US)
            val target = parts[1]
            val headers = LinkedHashMap<String, String>()
            while (true) {
                val line = input.readLine() ?: return
                if (line.isEmpty()) break
                val separator = line.indexOf(':')
                if (separator <= 0) {
                    sendError(output, 400)
                    return
                }
                val name = line.substring(0, separator).trim().lowercase(Locale.US)
                if (name.length > 128) {
                    sendError(output, 400)
                    return
                }
                headers[name] = line.substring(separator + 1).trim()
            }

            // A URI fragment is never sent over HTTP. Rejecting all query strings ensures a
            // caller cannot smuggle a second token or credentials into the path.
            if (target != "/$token") {
                sendError(output, 404)
                return
            }
            if (method != "GET" && method != "HEAD") {
                output.write(responseHeaders(405, mapOf(
                    "Allow" to "GET, HEAD",
                    "Content-Length" to "0",
                )).toByteArray(HTTP_CHARSET))
                output.flush()
                return
            }

            val parsedRange = SmbRangeProtocol.parseRangeHeader(headers["range"], reader.length)
            val response = SmbRangeProtocol.responsePolicy(parsedRange, reader.length)
            val extra = linkedMapOf<String, String>().apply {
                put("Accept-Ranges", "bytes")
                put("Content-Length", response.contentLength.toString())
                response.contentRange?.let { put("Content-Range", it) }
            }
            output.write(responseHeaders(response.status, extra).toByteArray(HTTP_CHARSET))
            if (method == "GET" && response.status != 416) {
                val selected = (parsedRange as? SmbRangeResult.Satisfied)?.range
                    ?: if (reader.length == 0L) null else SmbByteRange(0L, reader.length - 1L)
                selected?.let { stream(output, it, it.length) }
            }
            output.flush()
        }
    }

    private fun stream(output: OutputStream, range: SmbByteRange, expectedLength: Long) {
        var position = range.start
        var remaining = expectedLength
        val buffer = ByteArray(64 * 1024)
        while (remaining > 0L) {
            val request = minOf(remaining, buffer.size.toLong()).toInt()
            val count = reader.readAt(buffer, 0, request, position)
            if (count <= 0) throw IOException("SMB source ended before the requested range")
            output.write(buffer, 0, count)
            position += count
            remaining -= count
        }
    }

    private fun sendError(output: OutputStream, status: Int) {
        output.write(responseHeaders(status, mapOf("Content-Length" to "0")).toByteArray(HTTP_CHARSET))
        output.flush()
    }

    private fun responseHeaders(status: Int, extra: Map<String, String>): String = buildString {
        append("HTTP/1.1 ").append(SmbRangeProtocol.statusLine(status)).append("\r\n")
        append("Connection: close\r\n")
        extra.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
        append("\r\n")
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { socket.close() }
        workers.shutdownNow()
    }

    private class SmbThreadFactory(private val token: String) : ThreadFactory {
        override fun newThread(runnable: Runnable): Thread = Thread(runnable, "smb-http-worker-$token").apply {
            isDaemon = true
        }
    }

    companion object {
        private val HTTP_CHARSET: Charset = StandardCharsets.ISO_8859_1
    }
}

/** One open SMB file with reconnect-once semantics and no URI/credential logging. */
internal class SmbFileHandle(
    private val profile: SmbConnectionProfile,
    private val location: SmbLocation,
) : Closeable {
    private val resourcesLock = Any()
    private val closed = AtomicBoolean(false)
    @Volatile
    private var resources: Resources? = null

    fun open(): Long {
        check(!closed.get()) { "SMB file handle is closed" }
        val opened = openResources()
        synchronized(resourcesLock) {
            if (closed.get()) {
                opened.close()
                throw IOException("SMB file handle was closed while opening")
            }
            resources = opened
        }
        return opened.length
    }

    fun read(buffer: ByteArray, bufferOffset: Int, length: Int, fileOffset: Long): Int {
        if (closed.get()) throw IOException("SMB file handle is closed")
        val current = resources ?: throw IOException("SMB file handle is not open")
        val file = current.file ?: throw IOException("SMB file handle has no open file")
        return try {
            file.read(buffer, fileOffset, bufferOffset, length)
        } catch (error: Exception) {
            if (closed.get()) throw IOException("SMB file handle is closed", error)
            throw IOException("SMB read failed", error)
        }
    }

    fun reconnect() {
        if (closed.get()) throw IOException("SMB file handle is closed")
        val old: Resources?
        synchronized(resourcesLock) {
            old = resources
            resources = null
        }
        old?.close()
        val replacement = openResources()
        synchronized(resourcesLock) {
            if (closed.get()) {
                replacement.close()
                throw IOException("SMB file handle is closed")
            }
            resources = replacement
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val old: Resources?
        synchronized(resourcesLock) {
            old = resources
            resources = null
        }
        old?.close()
    }

    private fun openResources(): Resources {
        val client = SmbClientConfig.createClient()
        var connection: Connection? = null
        var session: Session? = null
        var share: DiskShare? = null
        var file: SmbFile? = null
        try {
            val connected = client.connect(profile.host, profile.port)
            connection = connected
            session = connected.authenticate(SmbClientConfig.authenticationContext(profile))
            val authenticated = session ?: throw IOException("SMB authentication returned no session")
            share = authenticated.connectShare(location.shareName) as? DiskShare
                ?: throw IOException("SMB share is not a disk share")
            file = share.openFile(
                location.path.replace('/', '\\'),
                EnumSet.of(AccessMask.FILE_READ_DATA, AccessMask.FILE_READ_ATTRIBUTES),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                EnumSet.of(
                    SMB2CreateOptions.FILE_NON_DIRECTORY_FILE,
                    SMB2CreateOptions.FILE_RANDOM_ACCESS,
                ),
            )
            val length = file.getFileInformation(FileStandardInformation::class.java).getEndOfFile()
            if (length < 0L) throw IOException("SMB file reported an invalid length")
            return Resources(client, connection, session, share, file, length)
        } catch (error: Exception) {
            Resources(client, connection, session, share, file, 0L).close()
            if (error is IOException) throw error
            throw IOException("Unable to connect to SMB media", error)
        }
    }

    private class Resources(
        private val client: SMBClient,
        private val connection: Connection?,
        private val session: Session?,
        private val share: DiskShare?,
        val file: SmbFile?,
        val length: Long,
    ) : Closeable {
        override fun close() {
            runCatching { file?.close() }
            runCatching { share?.close() }
            runCatching { session?.close() }
            // Force-closing the connection avoids SMBJ's lease counter retaining a dead socket;
            // this Resources instance owns the client and no other operation can use it.
            runCatching { connection?.close(true) }
            runCatching { client.close() }
        }
    }
}
