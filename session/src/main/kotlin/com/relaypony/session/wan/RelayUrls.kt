package com.relaypony.session.wan

/**
 * Relay base URL normalization (PROTOCOL_v3.md section 1). Scheme and host lowercased; default
 * port, path, query and trailing slash dropped; no scheme means https. The default relay
 * normalizes to the empty string. Matches `normalize_relay` in RelayPonyPake and the Swift twin.
 */
object RelayUrls {
    const val DEFAULT = "https://relaypony.app"

    fun normalize(url: String): String {
        val t = url.trim()
        if (t.isEmpty()) return ""
        val sep = t.indexOf("://")
        val scheme = if (sep >= 0) t.substring(0, sep).lowercase() else "https"
        val rest = if (sep >= 0) t.substring(sep + 3) else t
        var authority = rest.split('/', '?', '#').first().lowercase()
        val colon = authority.lastIndexOf(':')
        if (colon > 0) {
            val port = authority.substring(colon + 1)
            if ((scheme == "https" && port == "443") || (scheme == "http" && port == "80")) {
                authority = authority.substring(0, colon)
            }
        }
        val full = "$scheme://$authority"
        return if (full == DEFAULT) "" else full
    }

    /** The URL to actually talk to for a normalized relay value. */
    fun base(normalized: String): String = normalized.ifEmpty { DEFAULT }
}
