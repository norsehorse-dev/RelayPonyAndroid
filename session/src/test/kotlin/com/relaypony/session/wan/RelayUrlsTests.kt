package com.relaypony.session.wan

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Same cases as relay_normalization in RelayPonyPake. */
class RelayUrlsTests {
    @Test
    fun normalization() {
        assertEquals("", RelayUrls.normalize(""))
        assertEquals("", RelayUrls.normalize("https://relaypony.app"))
        assertEquals("", RelayUrls.normalize("HTTPS://RelayPony.app:443/api/signal"))
        assertEquals("https://relay.example.com", RelayUrls.normalize("https://Relay.Example.com/"))
        assertEquals("https://relay.example.com:8443", RelayUrls.normalize("https://relay.example.com:8443"))
        assertEquals("https://relay.example.com", RelayUrls.normalize("relay.example.com"))
        assertEquals("https://relaypony.app", RelayUrls.base(""))
    }
}
