package dev.neura.syncplay.smb

import java.util.Locale

/**
 * Credentials and endpoint information for one SMB server.
 *
 * Profiles are deliberately process-local.  The password is copied into a private character
 * array and is never part of the loopback URI used by MPV. [toString] is also redacted because profile
 * objects are frequently included in debug values while building a browser screen.
 */
class SmbConnectionProfile(
    val id: String,
    val host: String,
    val port: Int = DEFAULT_PORT,
    val username: String = "",
    password: CharArray = CharArray(0),
    val domain: String? = null,
    knownShares: List<String> = emptyList(),
) {
    /** Convenience overload for callers that receive a password from a text field. */
    constructor(
        id: String,
        host: String,
        port: Int = DEFAULT_PORT,
        username: String = "",
        password: String,
        domain: String? = null,
        knownShares: List<String> = emptyList(),
    ) : this(id, host, port, username, password.toCharArray(), domain, knownShares)

    private val secret = password.copyOf()

    /** Optional names to probe when a server does not expose share enumeration. */
    val knownShares: List<String> = knownShares.toList()

    init {
        SmbPathPolicy.validateProfileId(id)
        require(host.isNotBlank()) { "SMB host must not be blank" }
        require(host.none(Char::isISOControl)) { "SMB host contains a control character" }
        require(port in 1..65535) { "SMB port must be between 1 and 65535" }
        require(username.none(Char::isISOControl)) { "SMB username contains a control character" }
        require(domain?.none(Char::isISOControl) != false) {
            "SMB domain contains a control character"
        }
        knownShares.forEach(SmbPathPolicy::validateShareName)
    }

    internal fun passwordCopy(): CharArray = synchronized(this) { secret.copyOf() }

    internal fun copyForRegistry(): SmbConnectionProfile {
        val password = passwordCopy()
        return try {
            SmbConnectionProfile(
                id = id,
                host = host,
                port = port,
                username = username,
                password = password,
                domain = domain,
                knownShares = knownShares,
            )
        } finally {
            password.fill('\u0000')
        }
    }

    internal fun wipePassword() = synchronized(this) { secret.fill('\u0000') }

    /** Metadata safe to expose to a browser or diagnostic view. */
    fun summary(): SmbConnectionProfileSummary = SmbConnectionProfileSummary(
        id = id,
        host = host,
        port = port,
        username = username,
        domain = domain,
    )

    override fun equals(other: Any?): Boolean = other is SmbConnectionProfile &&
        id == other.id &&
        host.equals(other.host, ignoreCase = true) &&
        port == other.port &&
        username == other.username &&
        domain == other.domain &&
        knownShares == other.knownShares

    override fun hashCode(): Int = listOf(
        id,
        host.lowercase(Locale.ROOT),
        port,
        username,
        domain,
        knownShares,
    ).hashCode()

    override fun toString(): String =
        "SmbConnectionProfile(id=$id, host=$host, port=$port, username=$username, " +
            "domain=$domain, password=<redacted>)"

    companion object {
        const val DEFAULT_PORT = 445
    }
}

/** Password-free profile metadata suitable for Compose state and logs. */
data class SmbConnectionProfileSummary(
    val id: String,
    val host: String,
    val port: Int,
    val username: String,
    val domain: String?,
)

/** A file or directory returned by [SmbDirectoryRepository.list]. */
data class SmbDirectoryEntry(
    val name: String,
    /** Path relative to the share, using '/' separators. */
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModifiedMs: Long?,
) {
    val isFile: Boolean
        get() = !isDirectory
}

/** A disk share that can be opened by the current profile. */
data class SmbShareEntry(
    val name: String,
    val isDiskShare: Boolean = true,
)

/** A validated, credential-free location encoded by a [SmbUri]. */
data class SmbLocation(
    val profileId: String,
    val shareName: String,
    /** Path relative to [shareName], using '/' separators; empty means share root. */
    val path: String = "",
) {
    val isShareRoot: Boolean
        get() = path.isEmpty()

    fun asUri(): android.net.Uri = SmbUri.build(profileId, shareName, path)
}

/** Alias retained for callers that prefer the term resource to location. */
typealias SmbResource = SmbLocation

/** Result of a short-lived authenticated connection used by the browser. */
data class SmbConnectionInfo(
    val profile: SmbConnectionProfileSummary,
)
