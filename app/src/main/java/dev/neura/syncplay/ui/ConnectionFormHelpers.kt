package dev.neura.syncplay.ui

import dev.neura.syncplay.protocol.parseServerEndpoint

internal fun parsedServerHost(address: String): String? =
    runCatching { parseServerEndpoint(address).host.lowercase() }.getOrNull()

/** Clear once the edited address is valid and points away from the password's original host. */
internal fun shouldClearPasswordForServerHost(passwordHost: String?, nextAddress: String): Boolean {
    val nextHost = parsedServerHost(nextAddress) ?: return false
    return passwordHost != null && passwordHost != nextHost
}
