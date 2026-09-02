package dev.neura.syncplay.smb

import android.annotation.SuppressLint
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
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
import java.io.Closeable
import java.io.IOException
import java.util.EnumSet
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
 * Media3 DataSource backed by a single open SMB2/SMB3 file.
 *
 * Each source owns its SMB client and credentials are fetched from the process-local registry only
 * while opening a file. Reads use SMBJ's positional API, so a Media3 seek does not need to drain
 * bytes from the beginning of the file. Small sequential reads are served from a bounded (2 MiB
 * by default) window filled with one positional SMB READ. A failed read gets one fresh connection
 * before the error is returned to ExoPlayer.
 */
@SuppressLint("UnsafeOptInUsageError") // This class is the app's intentional Media3 DataSource boundary.
class SmbDataSource internal constructor(
    private val profiles: SmbConnectionProfileRegistry,
    private val maxReadChunkBytes: Int = DEFAULT_MAX_READ_CHUNK_BYTES,
    private val readAheadBytes: Int = DEFAULT_READ_AHEAD_BYTES,
) : BaseDataSource(/* isNetwork = */ true) {
    init {
        require(maxReadChunkBytes in MIN_READ_CHUNK_BYTES..MAX_READ_CHUNK_BYTES) {
            "SMB read chunk must be between $MIN_READ_CHUNK_BYTES and $MAX_READ_CHUNK_BYTES bytes"
        }
        require(readAheadBytes in MIN_READ_CHUNK_BYTES..MAX_READ_AHEAD_BYTES) {
            "SMB read-ahead must be between $MIN_READ_CHUNK_BYTES and $MAX_READ_AHEAD_BYTES bytes"
        }
    }

    // A caller may lower the SMB request cap for a particular server. Never allocate or request a
    // read-ahead window larger than that cap; this also keeps every request within the existing
    // 8 MiB safety bound.
    private val readAheadCapacityBytes =
        SmbReadAheadPolicy.effectiveCapacity(readAheadBytes, maxReadChunkBytes)

    private val stateLock = Any()
    private var handle: SmbFileHandle? = null
    private var requestUri: Uri? = null
    private var dataSpec: DataSpec? = null
    private var bytesRemaining = C.LENGTH_UNSET.toLong()
    private var position = 0L
    private var opened = false
    private var stateGeneration = 0L
    private var transferState = TransferState.NONE
    private var readAheadBuffer: ByteArray? = null
    private var readAheadOffset = 0
    private var readAheadLength = 0
    /** Serializes transfer callbacks only; SMB network I/O never runs while this is held. */
    private val transferCallbackLock = Any()

    override fun open(dataSpec: DataSpec): Long {
        // Media3 normally closes before opening again; closing defensively here keeps a failed
        // source from retaining a socket when a caller reuses a DataSource instance.
        close()
        transferInitializing(dataSpec)

        val location = try {
            SmbUri.parse(dataSpec.uri)
        } catch (error: IllegalArgumentException) {
            throw IOException("Invalid SMB media URI", error)
        }
        val profile = profiles.get(location.profileId)
            ?: throw IOException("Unknown SMB connection profile")
        val openGeneration = synchronized(stateLock) { stateGeneration }
        val newHandle = SmbFileHandle(profile, location)
        var transferStartNotified = false
        try {
            val fileLength = newHandle.open()
            val start = dataSpec.position
            require(start >= 0L) { "SMB data position must not be negative" }
            if (start > fileLength) {
                throw IOException("SMB data position is outside the file")
            }
            val requestedLength = dataSpec.length
            if (requestedLength != C.LENGTH_UNSET.toLong() && requestedLength < 0L) {
                throw IOException("SMB data length must not be negative")
            }
            val available = fileLength - start
            if (requestedLength != C.LENGTH_UNSET.toLong() && requestedLength > available) {
                throw IOException("SMB data range is outside the file")
            }
            val remaining = if (requestedLength == C.LENGTH_UNSET.toLong()) available else requestedLength

            synchronized(stateLock) {
                if (stateGeneration != openGeneration) {
                    // A concurrent close may have completed between the first close() and this
                    // assignment. Do not leak the newly opened handle in that case.
                    newHandle.close()
                    throw IOException("SMB data source was closed while opening")
                }
                handle = newHandle
                requestUri = dataSpec.uri
                this.dataSpec = dataSpec
                bytesRemaining = remaining
                position = start
                opened = true
                // Keep close() from reporting transferEnded before transferStarted has been
                // delivered when a provider closes the source concurrently with open().
                transferState = TransferState.STARTING
                // Allocate once per open and reuse for all small extractor reads. Keep tiny files
                // and bounded DataSpec ranges from paying for the full default window. A reopen /
                // seek gets a fresh empty window, so bytes from the previous DataSpec can never
                // leak.
                val windowBytes = SmbReadAheadPolicy.windowLength(
                    remainingBytes = remaining,
                    capacityBytes = readAheadCapacityBytes,
                )
                readAheadBuffer = windowBytes.takeIf { it > 0 }?.let { ByteArray(it) }
                readAheadOffset = 0
                readAheadLength = 0
            }
            synchronized(transferCallbackLock) {
                transferStarted(dataSpec)
                transferStartNotified = true
                val endedBeforeStart = synchronized(stateLock) {
                    if (opened && handle === newHandle && stateGeneration == openGeneration &&
                        transferState == TransferState.STARTING
                    ) {
                        transferState = TransferState.STARTED
                        false
                    } else {
                        // close() detached this request while the listener callback was running.
                        // Balance the callback now, after the state lock is released.
                        transferState = TransferState.NONE
                        true
                    }
                }
                if (endedBeforeStart) transferEnded()
            }
            return remaining
        } catch (error: IOException) {
            val endTransfer = clearFailedOpen(newHandle)
            newHandle.close()
            if (endTransfer && transferStartNotified) {
                synchronized(transferCallbackLock) { transferEnded() }
            }
            throw error
        } catch (error: Exception) {
            val endTransfer = clearFailedOpen(newHandle)
            newHandle.close()
            if (endTransfer && transferStartNotified) {
                synchronized(transferCallbackLock) { transferEnded() }
            }
            throw IOException("Unable to open SMB media", error)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (offset < 0 || length < 0 || offset > buffer.size - length) {
            throw IndexOutOfBoundsException("Invalid SMB read buffer range")
        }
        if (length == 0) return 0

        var bufferedCount = 0
        var readHandle: SmbFileHandle? = null
        var readGeneration = 0L
        var remotePosition = 0L
        var remoteRequestLength = 0
        var remoteBuffer: ByteArray? = null
        var remoteBufferOffset = 0
        var useReadAhead = false
        synchronized(transferCallbackLock) {
            synchronized(stateLock) {
                if (!opened) throw IOException("SMB data source is not open")
                if (bytesRemaining == 0L) return -1

                val available = readAheadLength
                if (available > 0) {
                    val source = readAheadBuffer
                        ?: throw IOException("SMB read-ahead buffer is missing")
                    bufferedCount = minOf(length, available, bytesRemaining.toIntSafely())
                    System.arraycopy(source, readAheadOffset, buffer, offset, bufferedCount)
                    position += bufferedCount
                    if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
                        bytesRemaining = (bytesRemaining - bufferedCount).coerceAtLeast(0L)
                    }
                    readAheadOffset += bufferedCount
                    readAheadLength -= bufferedCount
                    if (readAheadLength == 0) readAheadOffset = 0
                } else {
                    val activeHandle = handle ?: throw IOException("SMB data source has no open file")
                    readHandle = activeHandle
                    readGeneration = stateGeneration
                    remotePosition = position
                    val requested = minOf(length, maxReadChunkBytes, bytesRemaining.toIntSafely())
                    useReadAhead = SmbReadAheadPolicy.shouldReadAhead(
                        requestedBytes = requested,
                        capacityBytes = readAheadCapacityBytes,
                    )
                    remoteRequestLength = if (useReadAhead) {
                        SmbReadAheadPolicy.prefetchLength(
                            remainingBytes = bytesRemaining,
                            capacityBytes = readAheadCapacityBytes,
                            maxChunkBytes = maxReadChunkBytes,
                        )
                    } else {
                        requested
                    }
                    remoteBuffer = if (useReadAhead) {
                        readAheadBuffer ?: throw IOException("SMB read-ahead buffer is missing")
                    } else {
                        buffer
                    }
                    remoteBufferOffset = if (useReadAhead) 0 else offset
                }
            }
            if (bufferedCount > 0) bytesTransferred(bufferedCount)
        }

        if (bufferedCount > 0) {
            return bufferedCount
        }

        val activeHandle = readHandle ?: throw IOException("SMB data source has no open file")
        val target = remoteBuffer ?: throw IOException("SMB read target is missing")
        val count = try {
            activeHandle.read(target, remoteBufferOffset, remoteRequestLength, remotePosition)
        } catch (error: Exception) {
            // A dropped Wi-Fi connection or an SMB server idle timeout is recoverable. The
            // handle reopens at the requested offset and retries once; close() marks it terminal.
            // If close()/open() already invalidated this request, do not reconnect a terminal
            // handle or surface its cancellation as a media load error.
            if (!isCurrentRead(activeHandle, readGeneration, remotePosition)) return -1
            invalidateReadAheadForReconnect(activeHandle, readGeneration, remotePosition)
            try {
                activeHandle.reconnect()
                activeHandle.read(target, remoteBufferOffset, remoteRequestLength, remotePosition)
            } catch (retryError: Exception) {
                if (!isCurrentRead(activeHandle, readGeneration, remotePosition)) return -1
                if (retryError is IOException) throw retryError
                throw IOException("Unable to read SMB media", retryError)
            }
        }
        if (count < 0) {
            synchronized(stateLock) {
                if (opened && handle === activeHandle && stateGeneration == readGeneration &&
                    position == remotePosition
                ) {
                    bytesRemaining = 0L
                    readAheadOffset = 0
                    readAheadLength = 0
                }
            }
            return -1
        }
        if (count == 0) {
            // A zero-byte response for a positive request would make Media3 spin forever. Treat it
            // as a transport failure so the loader can apply its normal retry policy.
            throw IOException("SMB server returned an empty read")
        }
        if (count > remoteRequestLength) {
            throw IOException("SMB server returned more bytes than requested")
        }

        var deliveredCount = 0
        synchronized(transferCallbackLock) {
            synchronized(stateLock) {
                // close() or a concurrent read can race a network read. Do not update a newly
                // opened request, or advance the same position twice with stale bytes from
                // another read.
                if (!opened || handle !== activeHandle || stateGeneration != readGeneration ||
                    position != remotePosition
                ) {
                    return -1
                }
                if (useReadAhead) {
                    val source = readAheadBuffer
                    if (source == null || source !== target) {
                        return -1
                    }
                    readAheadOffset = 0
                    readAheadLength = count
                    deliveredCount = minOf(length, count, bytesRemaining.toIntSafely())
                    System.arraycopy(source, 0, buffer, offset, deliveredCount)
                    position += deliveredCount
                    if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
                        bytesRemaining = (bytesRemaining - deliveredCount).coerceAtLeast(0L)
                    }
                    readAheadOffset = deliveredCount
                    readAheadLength -= deliveredCount
                    if (readAheadLength == 0) readAheadOffset = 0
                } else {
                    deliveredCount = count
                    position += count
                    if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
                        bytesRemaining = (bytesRemaining - count).coerceAtLeast(0L)
                    }
                }
            }
            // Keep transferEnded() behind this callback when close() races the state commit. The
            // lock is acquired only after SMB I/O has completed.
            bytesTransferred(deliveredCount)
        }
        return deliveredCount
    }

    private fun isCurrentRead(
        expectedHandle: SmbFileHandle,
        expectedGeneration: Long,
        expectedPosition: Long,
    ): Boolean = synchronized(stateLock) {
        opened && handle === expectedHandle && stateGeneration == expectedGeneration &&
            position == expectedPosition
    }

    /** Reconnect starts a fresh transport, so discard any window tied to the failed transport. */
    private fun invalidateReadAheadForReconnect(
        expectedHandle: SmbFileHandle,
        expectedGeneration: Long,
        expectedPosition: Long,
    ) {
        synchronized(stateLock) {
            if (opened && handle === expectedHandle && stateGeneration == expectedGeneration &&
                position == expectedPosition
            ) {
                readAheadOffset = 0
                readAheadLength = 0
            }
        }
    }

    override fun getUri(): Uri? = synchronized(stateLock) { requestUri }

    override fun close() {
        val oldHandle: SmbFileHandle?
        val wasTransferStarted: Boolean
        synchronized(stateLock) {
            oldHandle = handle
            stateGeneration++
            handle = null
            requestUri = null
            dataSpec = null
            bytesRemaining = C.LENGTH_UNSET.toLong()
            position = 0L
            opened = false
            readAheadBuffer = null
            readAheadOffset = 0
            readAheadLength = 0
            wasTransferStarted = transferState == TransferState.STARTED
            // A STARTING transfer is balanced by open() after its callback returns. Only a
            // STARTED transfer can be ended here.
            transferState = TransferState.NONE
        }
        // Detach before closing so a blocked SMB read observes the terminal handle immediately.
        // End the transfer before the potentially blocking resource close, while serializing with
        // bytesTransferred() so listeners always see bytes before transferEnded().
        if (wasTransferStarted) {
            synchronized(transferCallbackLock) { transferEnded() }
        }
        oldHandle?.close()
    }

    /** Clear state when a listener/open failure occurs after the handle was attached. */
    private fun clearFailedOpen(expectedHandle: SmbFileHandle): Boolean = synchronized(stateLock) {
        if (handle !== expectedHandle) return@synchronized false
        val wasTransferStarted = transferState == TransferState.STARTED
        handle = null
        requestUri = null
        dataSpec = null
        bytesRemaining = C.LENGTH_UNSET.toLong()
        position = 0L
        opened = false
        readAheadBuffer = null
        readAheadOffset = 0
        readAheadLength = 0
        transferState = TransferState.NONE
        stateGeneration++
        wasTransferStarted
    }

    private fun Long.toIntSafely(): Int = when {
        this <= 0L -> 0
        this >= Int.MAX_VALUE.toLong() -> Int.MAX_VALUE
        else -> toInt()
    }

    /** Factory that resolves profile ids against a process-local registry. */
    @SuppressLint("UnsafeOptInUsageError")
    class Factory(
        private val profiles: SmbConnectionProfileRegistry,
        private val maxReadChunkBytes: Int = DEFAULT_MAX_READ_CHUNK_BYTES,
        private val readAheadBytes: Int = DEFAULT_READ_AHEAD_BYTES,
    ) : DataSource.Factory {
        private var transferListener: TransferListener? = null

        fun setTransferListener(listener: TransferListener?): Factory = apply {
            transferListener = listener
        }

        override fun createDataSource(): SmbDataSource = SmbDataSource(
            profiles = profiles,
            maxReadChunkBytes = maxReadChunkBytes,
            readAheadBytes = readAheadBytes,
        ).also { source -> transferListener?.let(source::addTransferListener) }
    }

    companion object {
        /** Default upper bound per SMB READ request (SMBJ further caps to its negotiated size). */
        const val DEFAULT_MAX_READ_CHUNK_BYTES = 8 * 1024 * 1024
        /** Default bounded window used to coalesce Media3's small sequential reads. */
        const val DEFAULT_READ_AHEAD_BYTES = 2 * 1024 * 1024
        const val MIN_READ_CHUNK_BYTES = 4 * 1024
        const val MAX_READ_CHUNK_BYTES = 8 * 1024 * 1024
        const val MAX_READ_AHEAD_BYTES = MAX_READ_CHUNK_BYTES
    }

    private enum class TransferState {
        NONE,
        STARTING,
        STARTED,
    }
}

/**
 * Selects SMB for [SmbUri.SCHEME] and preserves Media3's normal routing for every other scheme.
 * This factory can be passed to ExoPlayer's [androidx.media3.exoplayer.source.DefaultMediaSourceFactory]
 * without changing content/file/http playback behavior.
 */
@SuppressLint("UnsafeOptInUsageError")
class SmbRoutingDataSourceFactory(
    context: android.content.Context,
    profiles: SmbConnectionProfileRegistry,
    private val fallbackFactory: DataSource.Factory = DefaultDataSource.Factory(context),
    maxReadChunkBytes: Int = SmbDataSource.DEFAULT_MAX_READ_CHUNK_BYTES,
    readAheadBytes: Int = SmbDataSource.DEFAULT_READ_AHEAD_BYTES,
) : DataSource.Factory {
    private val smbFactory = SmbDataSource.Factory(profiles, maxReadChunkBytes, readAheadBytes)
    private var transferListener: TransferListener? = null

    fun setTransferListener(listener: TransferListener?): SmbRoutingDataSourceFactory = apply {
        transferListener = listener
    }

    override fun createDataSource(): DataSource = RoutingDataSource(
        fallbackFactory = fallbackFactory,
        smbFactory = smbFactory,
    ).also { source -> transferListener?.let(source::addTransferListener) }
}

@SuppressLint("UnsafeOptInUsageError")
private class RoutingDataSource(
    private val fallbackFactory: DataSource.Factory,
    private val smbFactory: DataSource.Factory,
) : DataSource {
    private val listeners = mutableListOf<TransferListener>()
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        synchronized(listeners) {
            if (!listeners.contains(transferListener)) listeners += transferListener
        }
        active?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        close()
        val selected = if (SmbUri.isSmbUri(dataSpec.uri)) {
            smbFactory.createDataSource()
        } else {
            fallbackFactory.createDataSource()
        }
        synchronized(listeners) { listeners.forEach(selected::addTransferListener) }
        active = selected
        return try {
            selected.open(dataSpec)
        } catch (error: IOException) {
            active = null
            selected.close()
            throw error
        } catch (error: Exception) {
            active = null
            selected.close()
            throw error
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        active?.read(buffer, offset, length) ?: throw IOException("Data source is not open")

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders.orEmpty()

    override fun close() {
        val source = active
        active = null
        source?.close()
    }
}

/** One open SMB file with reconnect-once semantics and no URI/credential logging. */
private class SmbFileHandle(
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
