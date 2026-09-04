package dev.neura.syncplay.player

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.FileDescriptor
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Coarse access capabilities discovered for a provider-backed media URI.
 *
 * A provider may expose the bytes through a regular seekable descriptor, or it may expose a
 * sequential pipe/socket.  [UNKNOWN] means that the provider could not be opened or the
 * descriptor did not answer the non-destructive probe.  In particular, UNKNOWN must not be
 * treated as proof that a source is seekable.
 */
enum class SourceAccessClassification(
    /** Whether random access is known to work; null when the probe was inconclusive. */
    val isSeekable: Boolean?,
) {
    SEEKABLE(true),
    SEQUENTIAL(false),
    UNKNOWN(null),
}

/**
 * Opens a content URI only long enough to inspect its descriptor type and seekability.
 *
 * No bytes are read and no descriptor retained by MPV is touched. The temporary descriptor is
 * always closed before this method returns.  Call this from a worker/IO dispatcher; document
 * providers are allowed to perform network work while opening a descriptor.
 */
object ContentUriAccessProbe {
    private val timeoutExecutor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "syncplay-content-access-timeout").apply { isDaemon = true }
    }

    /** Probe [uri] through [context]'s [ContentResolver]. */
    fun probe(context: Context, uri: Uri): SourceAccessClassification =
        probe(context.contentResolver, uri)

    /**
     * Probe [uri] through [resolver].  Non-content URIs are outside this probe's contract and
     * return [SourceAccessClassification.UNKNOWN].
     */
    fun probe(resolver: ContentResolver, uri: Uri): SourceAccessClassification {
        if (!uri.scheme.equals(ContentResolver.SCHEME_CONTENT, ignoreCase = true)) {
            return SourceAccessClassification.UNKNOWN
        }

        val cancellationSignal = CancellationSignal()
        val timeout = timeoutExecutor.schedule(
            cancellationSignal::cancel,
            PROBE_TIMEOUT_SECONDS,
            TimeUnit.SECONDS,
        )
        return try {
            // AssetFileDescriptor is what a number of DocumentsProviders implement first.  Its
            // close() owns the ParcelFileDescriptor, so only the outer descriptor is used with
            // `use` here.  Fall back to openFileDescriptor for providers that do not expose an
            // asset descriptor.
            val asset = try {
                resolver.openAssetFileDescriptor(uri, READ_MODE, cancellationSignal)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (asset != null) {
                return asset.use { classify(it.fileDescriptor) }
            }

            val descriptor = try {
                resolver.openFileDescriptor(uri, READ_MODE, cancellationSignal)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            descriptor?.use { classify(it.fileDescriptor) }
                ?: SourceAccessClassification.UNKNOWN
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Access probes are advisory.  A stale grant, a provider crash, or an unsupported
            // open mode must not prevent MPV from attempting its normal open path.
            SourceAccessClassification.UNKNOWN
        } finally {
            timeout.cancel(false)
        }
    }

    /**
     * Pure mapping kept separate from Android descriptor calls so the precedence is testable:
     * an actual pipe/socket remains sequential even if a provider's descriptor happens to
     * accept lseek().
     */
    internal fun classifyDescriptorAccess(
        seekable: Boolean?,
        isPipeOrSocket: Boolean,
    ): SourceAccessClassification = when {
        isPipeOrSocket -> SourceAccessClassification.SEQUENTIAL
        seekable == true -> SourceAccessClassification.SEEKABLE
        seekable == false -> SourceAccessClassification.SEQUENTIAL
        else -> SourceAccessClassification.UNKNOWN
    }

    private fun classify(descriptor: FileDescriptor): SourceAccessClassification {
        if (!descriptor.valid()) return SourceAccessClassification.UNKNOWN

        // fstat() does not consume bytes and lets us identify the descriptor kinds that are
        // inherently sequential.  A provider can still return a non-regular descriptor that
        // supports lseek(), so seekability is checked independently below.
        val mode = runCatching { Os.fstat(descriptor).st_mode }.getOrNull()
        val isPipeOrSocket = mode?.let {
            OsConstants.S_ISFIFO(it) || OsConstants.S_ISSOCK(it)
        } == true

        var originalOffset: Long? = null
        val seekable = try {
            // SEEK_CUR with a zero displacement is a capability probe: it leaves the descriptor
            // at exactly the same position and does not read or write provider data.
            originalOffset = Os.lseek(descriptor, 0L, OsConstants.SEEK_CUR)
            true
        } catch (error: ErrnoException) {
            if (error.errno == OsConstants.ESPIPE) false else null
        } catch (_: Exception) {
            null
        } finally {
            // Keep the temporary descriptor's position unchanged even for providers that expose
            // a non-zero initial offset through an AssetFileDescriptor.
            originalOffset?.let { offset ->
                runCatching { Os.lseek(descriptor, offset, OsConstants.SEEK_SET) }
            }
        }

        return classifyDescriptorAccess(seekable, isPipeOrSocket)
    }

    private const val READ_MODE = "r"
    private const val PROBE_TIMEOUT_SECONDS = 5L
}
