package dev.neura.syncplay.player

import android.content.ContentResolver
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.CancellationSignal
import android.provider.OpenableColumns
import dev.neura.syncplay.smb.SmbUri
import dev.neura.syncplay.protocol.MediaDescriptor
import kotlinx.coroutines.CancellationException
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Metadata resolved for a local media item selected through the Storage Access
 * Framework (or a regular file URI).
 *
 * [displayName] and [sizeBytes] are intentionally resolved through
 * [ContentResolver] first.  A `content://` URI is not guaranteed to have a
 * filesystem path, so callers must not use `Uri.path` as the file name or size.
 * Duration is best-effort: providers are allowed to omit metadata and some
 * codecs do not expose a duration until the player has prepared the item.
 */
data class ResolvedMediaInfo(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    val durationSeconds: Double,
    val mimeType: String? = null,
    /**
     * Whether a provider-backed source exposes a descriptor that supports random access.
     * [SourceAccessClassification.UNKNOWN] is also used for non-content streams.
     */
    val sourceAccess: SourceAccessClassification = SourceAccessClassification.UNKNOWN,
) {
    val descriptor: MediaDescriptor
        get() = MediaDescriptor(
            name = displayName,
            durationSeconds = durationSeconds,
            sizeBytes = sizeBytes,
        )
}

/**
 * Resolves stable metadata for the local media URI used by ExoPlayer and by
 * the Syncplay file descriptor.  All methods are synchronous; use them from
 * a worker dispatcher when called by UI or service code.
 */
object MediaInfoResolver {
    private val nameProjection = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
    private val metadataTimeoutExecutor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "syncplay-metadata-timeout").apply { isDaemon = true }
    }

    /**
     * Resolve metadata without throwing for provider/codec-specific failures.
     * A useful fallback descriptor is returned even when duration is unknown.
     */
    fun resolve(context: Context, uri: Uri): ResolvedMediaInfo {
        val resolver = context.contentResolver
        val smbScheme = SmbUri.isSmbUri(uri)
        // Keep malformed private-scheme URIs out of the local-file fallback and do not advertise
        // them as seekable. The SMB data source will still return the actionable URI error if a
        // caller tries to prepare one.
        val directSmb = smbScheme && runCatching { SmbUri.parse(uri) }.isSuccess
        val (queriedName, queriedSize) = if (smbScheme) {
            null to null
        } else {
            queryOpenableMetadata(resolver, uri)
        }
        val displayName = queriedName
            ?.takeIf { it.isNotBlank() }
            ?: fallbackDisplayName(uri)
        val sizeBytes = if (smbScheme) {
            // A syncplaysmb path is relative to a remote share. Never pass its URI path to
            // java.io.File, which would probe an unrelated local path such as /Movies/title.mkv.
            0L
        } else {
            queriedSize
                ?.takeIf { it >= 0L }
                ?: fallbackFileSize(uri)
        }
        // A content provider may be backed by a network filesystem (for example
        // an SMB location exposed by a file manager).  MediaMetadataRetriever
        // performs a synchronous read when given a content URI and some
        // providers do not return until the remote file has been opened.  That
        // probe can take longer than Syncplay's heartbeat timeout.  Media3 will
        // discover the duration after prepare(), so leave it unknown here for
        // all provider URIs and keep the control connection independent.
        val durationSeconds = if (isContentUri(uri) || smbScheme) {
            0.0
        } else {
            queryDurationSeconds(context, uri)
        }
        // Probe through a separate, short-lived descriptor.  This never consumes the descriptor
        // that Media3 will open and callers already invoke resolve() on a worker dispatcher.
        val sourceAccess = if (directSmb) {
            SourceAccessClassification.SEEKABLE
        } else if (smbScheme) {
            SourceAccessClassification.UNKNOWN
        } else {
            ContentUriAccessProbe.probe(resolver, uri)
        }
        return ResolvedMediaInfo(
            uri = uri,
            displayName = displayName,
            sizeBytes = sizeBytes,
            durationSeconds = durationSeconds,
            // Media3 and subtitle extension mapping can infer provider content. Avoid a second
            // potentially remote provider call after the bounded metadata query.
            mimeType = if (isContentUri(uri) || smbScheme) {
                null
            } else {
                runCatching { resolver.getType(uri) }.getOrNull()
            },
            sourceAccess = sourceAccess,
        )
    }

    /** Resolve just the descriptor used in the Syncplay `Set.file` payload. */
    fun resolveDescriptor(context: Context, uri: Uri): MediaDescriptor = resolve(context, uri).descriptor

    private fun queryOpenableMetadata(
        resolver: ContentResolver,
        uri: Uri,
    ): Pair<String?, Long?> {
        // Some providers throw SecurityException/IllegalArgumentException for
        // a stale or revoked URI.  Metadata should never make opening fail.
        val cancellationSignal = CancellationSignal()
        val timeout = metadataTimeoutExecutor.schedule(
            cancellationSignal::cancel,
            METADATA_QUERY_TIMEOUT_SECONDS,
            TimeUnit.SECONDS,
        )
        return try {
            resolver.query(uri, nameProjection, null, null, null, cancellationSignal)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null to null
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                val name = nameIndex.takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let(cursor::getString)
                val size = sizeIndex.takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let { index -> cursor.getLong(index) }
                name to size
            } ?: (null to null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null to null
        } finally {
            timeout.cancel(false)
        }
    }

    private fun fallbackDisplayName(uri: Uri): String {
        val pathPart = uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
        if (pathPart != null) return Uri.decode(pathPart)

        // A provider can expose neither DISPLAY_NAME nor a useful path.  Keep
        // this value deterministic and non-empty for the wire descriptor.
        return String.format(Locale.ROOT, "media-%08x", uri.toString().hashCode())
    }

    private fun fallbackFileSize(uri: Uri): Long {
        if (uri.scheme.equals(ContentResolver.SCHEME_CONTENT, ignoreCase = true)) return 0L
        val path = uri.path ?: return 0L
        return runCatching { File(path).length().coerceAtLeast(0L) }.getOrDefault(0L)
    }

    private fun queryDurationSeconds(context: Context, uri: Uri): Double {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val durationMillis = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: 0L
            if (durationMillis <= 0L) 0.0 else durationMillis / 1_000.0
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // IllegalArgumentException, IOException and provider-specific
            // RuntimeExceptions are all possible for SAF streams.
            0.0
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun isContentUri(uri: Uri): Boolean =
        uri.scheme.equals(ContentResolver.SCHEME_CONTENT, ignoreCase = true)

    private const val METADATA_QUERY_TIMEOUT_SECONDS = 5L
}
