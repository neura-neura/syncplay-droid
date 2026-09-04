package dev.neura.syncplay.smb

import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import java.util.concurrent.TimeUnit

/**
 * Builds the SMBJ client configuration used by both browsing and playback.
 *
 * SMB reads are long-lived and usually happen over Wi-Fi, a VPN, or a ZeroTier link.  The
 * explicit dialect list keeps negotiation on SMB2/SMB3 (SMB1 is never enabled), the bounded
 * socket/read timeouts let the MPV bridge retry a dropped connection, and larger buffers avoid
 * turning a sequential extractor read into thousands of small SMB requests.  Signing remains
 * enabled (it is optional rather than required), while SMB encryption stays disabled for the
 * Android-compatible SMBJ path used by this app.
 */
internal object SmbClientConfig {
    private const val BUFFER_BYTES = 8 * 1024 * 1024
    private const val READ_TIMEOUT_SECONDS = 30L
    private const val SOCKET_TIMEOUT_SECONDS = 35L

    fun createClient(): SMBClient = SMBClient(
        SmbConfig.builder()
            .withTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .withSoTimeout(SOCKET_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .withDialects(
                SMB2Dialect.SMB_3_1_1,
                SMB2Dialect.SMB_3_0_2,
                SMB2Dialect.SMB_3_0,
                SMB2Dialect.SMB_2_1,
                SMB2Dialect.SMB_2_0_2,
            )
            .withDfsEnabled(false)
            .withMultiProtocolNegotiate(true)
            .withSigningRequired(false)
            .withEncryptData(false)
            .withReadBufferSize(BUFFER_BYTES)
            .withWriteBufferSize(BUFFER_BYTES)
            .build(),
    )

    /**
     * Creates an SMBJ authentication context without retaining the registry's temporary password
     * copy.  An empty user and empty password must use SMBJ's anonymous context; sending an empty
     * NTLM user through the regular constructor is rejected by some guest-only Windows shares.
     */
    fun authenticationContext(profile: SmbConnectionProfile): AuthenticationContext {
        val password = profile.passwordCopy()
        return try {
            if (profile.username.isEmpty() && password.isEmpty()) {
                AuthenticationContext.anonymous()
            } else {
                AuthenticationContext(profile.username, password, profile.domain)
            }
        } finally {
            password.fill('\u0000')
        }
    }
}
