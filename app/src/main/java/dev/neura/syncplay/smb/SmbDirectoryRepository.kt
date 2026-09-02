package dev.neura.syncplay.smb

import android.net.Uri
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.io.Closeable
import java.io.IOException
import java.util.EnumSet

/**
 * Small blocking repository intended to be called from a browser ViewModel's IO dispatcher.
 * Connections are short-lived per operation; this keeps a browser disconnect from invalidating a
 * selected playback URI and lets the data source reconnect independently.
 */
class SmbDirectoryRepository(
    private val profiles: SmbConnectionProfileRegistry,
) : Closeable {
    /** Register credentials in the process-local registry and verify authentication. */
    fun connect(profile: SmbConnectionProfile): SmbConnectionInfo {
        profiles.register(profile)
        return connect(profile.id)
    }

    /** Verify authentication for an already registered profile. */
    fun connect(profileId: String): SmbConnectionInfo {
        val profile = profiles.require(profileId)
        withConnection(profile) { /* authentication is the operation */ }
        return SmbConnectionInfo(profile.summary())
    }

    /** List one share directory. [path] is relative to the share and may use either slash style. */
    fun list(profileId: String, shareName: String, path: String = ""): List<SmbDirectoryEntry> {
        val profile = profiles.require(profileId)
        return withDiskShare(profile, shareName) { share ->
            listEntries(share, shareName, path)
        }
    }

    fun list(location: SmbLocation): List<SmbDirectoryEntry> =
        list(location.profileId, location.shareName, location.path)

    fun list(uri: Uri): List<SmbDirectoryEntry> = list(SmbUri.parse(uri))

    /**
     * Probe known share names and return only disk shares accepted by the current credentials.
     * SMBJ does not expose the optional SRVSVC share-enumeration RPC, so callers should pass names
     * supplied by the user/device or [SmbConnectionProfile.knownShares]. An empty candidate list
     * returns an empty result without opening a network connection.
     */
    fun listShares(profile: SmbConnectionProfile): List<SmbShareEntry> =
        listShares(profile, profile.knownShares)

    fun listShares(profileId: String): List<SmbShareEntry> {
        val profile = profiles.require(profileId)
        return listShares(profile, profile.knownShares)
    }

    /** Probe candidate names, filtering out inaccessible or non-disk trees. */
    fun listShares(
        profile: SmbConnectionProfile,
        candidateNames: Iterable<String>,
    ): List<SmbShareEntry> {
        val names = candidateNames
            .map(SmbPathPolicy::validateShareName)
            .distinct()
        if (names.isEmpty()) return emptyList()
        // A caller may pass a profile not yet registered when using this overload. Keep the
        // registry as the source of truth for all subsequent playback operations.
        profiles.register(profile)
        return names.mapNotNull { name ->
            runCatching {
                withConnection(profile) { session ->
                    val share = session.connectShare(name)
                    try {
                        if (share is DiskShare) SmbShareEntry(name) else null
                    } finally {
                        runCatching { share.close() }
                    }
                }
            }.getOrNull()
        }
    }

    fun listShares(profileId: String, candidateNames: Iterable<String>): List<SmbShareEntry> =
        listShares(profiles.require(profileId), candidateNames)

    /** There is no retained connection to release; each repository operation closes its scope. */
    override fun close() = Unit

    private fun listEntries(
        share: DiskShare,
        shareName: String,
        path: String,
    ): List<SmbDirectoryEntry> {
        val normalizedPath = SmbPathPolicy.normalizeRelativePath(path)
        val remotePath = normalizedPath.replace('/', '\\')
        return share.list(remotePath, FileIdBothDirectoryInformation::class.java)
            .asSequence()
            .mapNotNull { info -> info.toEntry(normalizedPath) }
            .sortedWith(compareBy<SmbDirectoryEntry> { !it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .toList()
    }

    private fun FileIdBothDirectoryInformation.toEntry(parentPath: String): SmbDirectoryEntry? {
        val entryName = getFileName()
        if (entryName == "." || entryName == ".." || entryName.isBlank()) return null
        val childPath = runCatching {
            SmbPathPolicy.normalizeRelativePath(
                if (parentPath.isEmpty()) entryName else "$parentPath/$entryName",
            )
        }.getOrNull() ?: return null
        val directory = getFileAttributes() and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue() != 0L
        val modified = runCatching { getLastWriteTime()?.toEpochMillis() }.getOrNull()
        return SmbDirectoryEntry(
            name = entryName,
            path = childPath,
            isDirectory = directory,
            sizeBytes = if (directory) 0L else getEndOfFile().coerceAtLeast(0L),
            lastModifiedMs = modified,
        )
    }

    private fun <T> withDiskShare(
        profile: SmbConnectionProfile,
        shareName: String,
        block: (DiskShare) -> T,
    ): T {
        val validShare = SmbPathPolicy.validateShareName(shareName)
        return withConnection(profile) { session ->
            val share = session.connectShare(validShare)
            if (share !is DiskShare) {
                runCatching { share.close() }
                throw IOException("SMB share is not a disk share")
            }
            try {
                block(share)
            } finally {
                runCatching { share.close() }
            }
        }
    }

    private fun <T> withConnection(
        profile: SmbConnectionProfile,
        block: (Session) -> T,
    ): T {
        val client = SmbClientConfig.createClient()
        var connection: Connection? = null
        var session: Session? = null
        try {
            val connected = client.connect(profile.host, profile.port)
            connection = connected
            session = connected.authenticate(SmbClientConfig.authenticationContext(profile))
            val authenticated = session ?: throw IOException("SMB authentication returned no session")
            return block(authenticated)
        } catch (error: IOException) {
            throw error
        } catch (error: Exception) {
            throw IOException("Unable to connect to SMB server", error)
        } finally {
            runCatching { session?.close() }
            runCatching { connection?.close(true) }
            client.close()
        }
    }
}
