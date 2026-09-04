package dev.neura.syncplay.smb

import android.net.Uri
import java.io.Closeable
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-local registry for SMB credentials.
 *
 * The registry intentionally has no persistence mechanism.  Registering a profile copies its
 * secret, replacing/removing a profile wipes the old character array, and callers can only obtain
 * password-free summaries.  Playback URIs carry the opaque profile id and therefore remain safe to
 * put in media metadata or Syncplay diagnostics.
 */
class SmbConnectionProfileRegistry {
    private val profiles = ConcurrentHashMap<String, SmbConnectionProfile>()

    /** Add or replace a profile, returning metadata that is safe to retain in UI state. */
    fun register(profile: SmbConnectionProfile): SmbConnectionProfileSummary {
        val stored = profile.copyForRegistry()
        val previous = profiles.put(stored.id, stored)
        previous?.wipePassword()
        return stored.summary()
    }

    /** Convenience registration overload for a text-field password. */
    fun register(
        id: String,
        host: String,
        port: Int = SmbConnectionProfile.DEFAULT_PORT,
        username: String = "",
        password: String = "",
        domain: String? = null,
        knownShares: List<String> = emptyList(),
    ): SmbConnectionProfileSummary = register(
        SmbConnectionProfile(id, host, port, username, password, domain, knownShares),
    )

    /** Resolve an opaque id for an in-process SMB operation. */
    fun get(id: String): SmbConnectionProfile? = profiles[id]

    fun require(id: String): SmbConnectionProfile = get(id)
        ?: throw IllegalArgumentException("Unknown SMB profile")

    /** Alias useful to browser code that reads like a lookup rather than a map access. */
    fun profile(id: String): SmbConnectionProfile? = get(id)

    fun contains(id: String): Boolean = profiles.containsKey(id)

    fun summaries(): List<SmbConnectionProfileSummary> = profiles.values
        .map(SmbConnectionProfile::summary)
        .sortedBy { it.id }

    /** Remove one profile and wipe its in-memory password. */
    fun unregister(id: String): Boolean = profiles.remove(id)?.let {
        it.wipePassword()
        true
    } ?: false

    /** Wipe all credentials and forget all profile ids. */
    fun clear() {
        profiles.values.forEach(SmbConnectionProfile::wipePassword)
        profiles.clear()
    }
}

/**
 * Shared process environment used by browser and playback code.  It is not persisted and should
 * be cleared when the app explicitly signs out or forgets SMB credentials.
 */
object SmbPlaybackEnvironment : Closeable {
    val registry: SmbConnectionProfileRegistry = SmbConnectionProfileRegistry()
    val directoryRepository: SmbDirectoryRepository = SmbDirectoryRepository(registry)
    private val activeMpvSources = Collections.newSetFromMap(
        ConcurrentHashMap<SmbMpvSource, Boolean>(),
    )

    /**
     * Open [uri] for libmpv. The returned URL is loopback-only and contains an opaque token; the
     * source remains valid until its [SmbMpvSource.close] method is called.
     */
    fun openMpvSource(uri: Uri): SmbMpvSource = openSmbMpvSource(uri, registry).also { source ->
        source.onClosed = { activeMpvSources -= it }
        activeMpvSources += source
    }

    /** Open [uri] as a sequential stream for subtitle/metadata readers. */
    fun openInputStream(uri: Uri): java.io.InputStream = openSmbInputStream(uri, registry)

    override fun close() {
        activeMpvSources.toList().forEach { source -> runCatching { source.close() } }
        activeMpvSources.clear()
        directoryRepository.close()
        registry.clear()
    }
}
