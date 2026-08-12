package com.relaypony.transport

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WireNegotiationTests {

    @Test
    fun version_picksMinFlooredAtOne() {
        assertEquals(2, WireNegotiation.version(2, 2))
        assertEquals(1, WireNegotiation.version(2, 1))   // v1 peer
        assertEquals(1, WireNegotiation.version(1, 2))   // we are the old build
        assertEquals(1, WireNegotiation.version(2, 0))   // garbage advert -> v1 floor
        assertEquals(2, WireNegotiation.version(2, 9))   // never above our own max
    }

    @Test
    fun effectiveCaps_isIntersection() {
        val a = WireProtocol.CAP_WANT_ACK_FLOW or WireProtocol.CAP_RESUME
        val b = WireProtocol.CAP_WANT_ACK_FLOW or WireProtocol.CAP_SHA256
        assertEquals(WireProtocol.CAP_WANT_ACK_FLOW, WireNegotiation.effectiveCaps(a, b))
        assertEquals(0, WireNegotiation.effectiveCaps(0, a))
        assertEquals(a, WireNegotiation.effectiveCaps(a, a))
    }
}
