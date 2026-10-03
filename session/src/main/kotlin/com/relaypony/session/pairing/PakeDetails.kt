package com.relaypony.session.pairing

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * What each side of a word-code pairing sends the other under the PAKE key (PROTOCOL_v3.md
 * section 7.3): `RPX1|<handle>|<form(name)>|<inboxId>|<form(relay)>`. Both sides pin each other
 * from these, with no code comparison, because the PAKE already authenticated the shared code.
 */
data class PakeDetails(val handle: String, val name: String, val inboxId: String, val relay: String) {
    fun encode(): String = listOf(
        "RPX1", handle, URLEncoder.encode(name, "UTF-8"), inboxId, URLEncoder.encode(relay, "UTF-8"),
    ).joinToString("|")

    companion object {
        fun decode(text: String): PakeDetails {
            val p = text.split("|")
            require(p.size == 5 && p[0] == "RPX1") { "not pairing details" }
            require(p[1].startsWith("age1")) { "bad handle" }
            require(InboxIds.isValid(p[3])) { "bad inbox id" }
            return PakeDetails(p[1], URLDecoder.decode(p[2], "UTF-8"), p[3], URLDecoder.decode(p[4], "UTF-8"))
        }
    }
}
