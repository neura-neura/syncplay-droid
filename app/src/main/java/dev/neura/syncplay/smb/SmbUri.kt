package dev.neura.syncplay.smb

import android.net.Uri

/** Pure validation and canonicalisation rules shared by the browser and playback source. */
object SmbPathPolicy {
    private const val MAX_PROFILE_ID_LENGTH = 64
    private const val MAX_SHARE_NAME_LENGTH = 80
    private const val MAX_PATH_LENGTH = 32_768

    /** Profile identifiers are opaque registry keys, never host names or credentials. */
    fun validateProfileId(value: String): String {
        val id = value.trim()
        require(id == value) { "SMB profile id must not have surrounding whitespace" }
        require(id.isNotEmpty() && id.length <= MAX_PROFILE_ID_LENGTH) {
            "SMB profile id must contain 1-$MAX_PROFILE_ID_LENGTH characters"
        }
        require(id.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' || it == '~' }) {
            "SMB profile id contains an unsupported character"
        }
        return id
    }

    /** Share names are one SMB tree component and cannot contain path separators. */
    fun validateShareName(value: String): String {
        val share = value.trim()
        require(share == value) { "SMB share name must not have surrounding whitespace" }
        require(share.isNotEmpty() && share.length <= MAX_SHARE_NAME_LENGTH) {
            "SMB share name must contain 1-$MAX_SHARE_NAME_LENGTH characters"
        }
        require(share != "." && share != "..") { "SMB share name is not valid" }
        require(share.none { it == '/' || it == '\\' || it == '\u0000' || it.isISOControl() }) {
            "SMB share name contains an unsupported character"
        }
        return share
    }

    /**
     * Canonicalises a path to slash separators and rejects traversal/absolute paths.
     * Empty path denotes the share root. Empty components are ignored so either slash style can
     * be supplied by a browser implementation.
     */
    fun normalizeRelativePath(value: String): String {
        require(value.length <= MAX_PATH_LENGTH) { "SMB path is too long" }
        require(value.none { it == '\u0000' || it.isISOControl() }) {
            "SMB path contains a control character"
        }
        val slashPath = value.replace('\\', '/')
        require(!slashPath.startsWith('/')) { "SMB path must be relative" }
        require(!Regex("^[A-Za-z]:($|/)").containsMatchIn(slashPath)) {
            "SMB path must not contain a drive prefix"
        }
        val parts = slashPath.split('/').filter(String::isNotEmpty)
        require(parts.none { it == "." || it == ".." }) { "SMB path traversal is not allowed" }
        return parts.joinToString("/")
    }

    fun pathSegments(value: String): List<String> = normalizeRelativePath(value)
        .takeIf(String::isNotEmpty)
        ?.split('/')
        .orEmpty()
}

/**
 * Credential-free URI codec for direct SMB playback.
 *
 * Format: {@code syncplaysmb://<profile-id>/<share>/<path>}.  The profile id resolves to an
 * in-process [SmbConnectionProfileRegistry]; no user name or password can be represented here.
 */
object SmbUri {
    const val SCHEME = "syncplaysmb"

    fun isSmbUri(uri: Uri): Boolean = uri.scheme.equals(SCHEME, ignoreCase = true)

    fun isSmbUri(value: String): Boolean = runCatching { isSmbUri(Uri.parse(value)) }.getOrDefault(false)

    fun parse(value: String): SmbLocation = parse(Uri.parse(value))

    fun parse(uri: Uri): SmbLocation {
        require(isSmbUri(uri)) { "Expected a $SCHEME URI" }
        require(uri.query == null && uri.fragment == null) {
            "SMB URI must not contain a query or fragment"
        }
        val authority = uri.encodedAuthority
            ?.takeIf(String::isNotEmpty)
            ?: throw IllegalArgumentException("SMB URI is missing its profile id")
        // Uri.host strips user-info and may hide malformed authorities. Requiring the exact
        // authority shape keeps credentials and alternate ports out of this private scheme.
        require(!authority.contains('@') && !authority.contains(':')) {
            "SMB URI authority must be a profile id, not user-info or a host:port"
        }
        val profileId = SmbPathPolicy.validateProfileId(Uri.decode(authority))
        val segments = uri.pathSegments
        require(segments.isNotEmpty()) { "SMB URI is missing its share name" }
        val shareName = SmbPathPolicy.validateShareName(segments.first())
        val path = SmbPathPolicy.normalizeRelativePath(segments.drop(1).joinToString("/"))
        return SmbLocation(profileId, shareName, path)
    }

    fun build(profileId: String, shareName: String, path: String = ""): Uri {
        val profile = SmbPathPolicy.validateProfileId(profileId)
        val share = SmbPathPolicy.validateShareName(shareName)
        val segments = listOf(share) + SmbPathPolicy.pathSegments(path)
        val encodedPath = segments.joinToString(
            separator = "/",
            prefix = "/",
        ) { Uri.encode(it, URI_SEGMENT_ALLOWED) }
        return Uri.Builder()
            .scheme(SCHEME)
            .encodedAuthority(Uri.encode(profile, PROFILE_ALLOWED))
            .encodedPath(encodedPath)
            .build()
    }

    fun build(location: SmbLocation): Uri = build(location.profileId, location.shareName, location.path)

    private const val PROFILE_ALLOWED = "-_.~"
    // Keep common SMB filename punctuation readable while always encoding slash, query and '#'.
    private const val URI_SEGMENT_ALLOWED = "-_.~!$&'()*+,;=:@"
}
