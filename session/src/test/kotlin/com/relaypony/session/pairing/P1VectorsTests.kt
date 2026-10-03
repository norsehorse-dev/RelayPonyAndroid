package com.relaypony.session.pairing

import com.relaypony.crypto.AgeProvider
import com.relaypony.crypto.PeerKey
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Protocol v3 vectors. The expected values come from vectors/gen_p1_vectors.py, an independent
 * Python implementation; the Swift suite asserts the same values.
 */
class P1VectorsTests {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private val scalarA = hex("0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20")
    private val scalarB = hex("201f1e1d1c1b1a191817161514131211100f0e0d0c0b0a090807060504030201")
    private val handleA = "age1q73he0q5yzfu3d64msd3p6rvksnrwjk3d2598mgtmlqt9wrdr37q2vrn72"
    private val handleB = "age1p4uevq8kl7hw9cfpu6u00gzace58fdgakvgz6rt377v6p895c3ss5ae3ad"
    private val nonceA = ByteArray(16) { it.toByte() }
    private val nonceB = ByteArray(16) { (it + 0x10).toByte() }
    private val inboxA = B64u.encode(ByteArray(32) { (it + 0x40).toByte() })
    private val inboxB = B64u.encode(ByteArray(32) { (it + 0x60).toByte() })

    @Test
    fun b64u_roundTripsAndMatchesReference() {
        assertEquals("AAECAwQFBgcICQoLDA0ODw", B64u.encode(nonceA))
        assertEquals("QEFCQ0RFRkdISUpLTE1OT1BRUlNUVVZXWFlaW1xdXl8", inboxA)
        for (n in 0..40) {
            val b = ByteArray(n) { (it * 37).toByte() }
            assertArrayEquals(b, B64u.decode(B64u.encode(b)))
        }
        assertThrows(IllegalArgumentException::class.java) { B64u.decode("ab+c") }
        assertThrows(IllegalArgumentException::class.java) { B64u.decode("A") }
    }

    @Test
    fun sasV2_matchesReference() {
        assertEquals("1b65b27995fee5587d8e4faad1b6d3b2dae87bc2c78f06193b4282a297490b85",
            SasV2.digest(handleA, handleB, nonceA, nonceB).joinToString("") { "%02x".format(it) })
        assertEquals("49127512", SasV2.code(handleA, handleB, nonceA, nonceB))
        assertEquals("49127512", SasV2.code(handleB, handleA, nonceA, nonceB))   // handle order doesn't matter
        assertEquals("11621758", SasV2.code(handleA, handleB, nonceB, nonceA))   // nonce roles do
        assertEquals("4912 7512", SasV2.display("49127512"))
    }

    @Test
    fun qrV2_matchesReference() {
        val def = "RP2|01|$handleA|Test+Phone+8|AAECAwQFBgcICQoLDA0ODw|$inboxA|"
        val custom = "RP2|01|$handleA|Test+Phone+8|AAECAwQFBgcICQoLDA0ODw|$inboxA|https%3A%2F%2Frelay.example.com"
        assertEquals(def, QrPayloadV2(0x01, handleA, "Test Phone 8", nonceA, inboxA).encode())
        assertEquals(custom, QrPayloadV2(0x01, handleA, "Test Phone 8", nonceA, inboxA, "https://relay.example.com").encode())
        val back = QrPayloadV2.decode(custom)
        assertEquals("https://relay.example.com", back.relay)
        assertArrayEquals(nonceA, back.pairNonce)
        assertTrue(ScannedQr.decode(def) is ScannedQr.V2)
        assertTrue(ScannedQr.decode("RP1|01|$handleA|Old+Phone") is ScannedQr.V1)
        assertThrows(IllegalArgumentException::class.java) { QrPayloadV2.decode(def.replace("AAECAwQFBgcICQoLDA0ODw", "AAEC")) }
        assertThrows(IllegalArgumentException::class.java) { QrPayloadV2.decode("$def|extra") }
    }

    @Test
    fun pairRequest_matchesReference() {
        val req = PairMessages.PairRequest(handleB, "Test Tablet", inboxB, "", nonceA, nonceB)
        val ref = "RPQ1|$handleB|Test+Tablet|YGFiY2RlZmdoaWprbG1ub3BxcnN0dXZ3eHl6e3x9fn8||AAECAwQFBgcICQoLDA0ODw|EBESExQVFhcYGRobHB0eHw"
        assertEquals(ref, req.encode())
        assertEquals(req, PairMessages.PairRequest.decode(ref))
    }

    @Test
    fun pairAck_matchesReferenceAndVerifiesOnB() {
        assertEquals("8aacb989f8967b82d662526b4b4a745551a229c6adadece883692177725388b1",
            PeerKey.deriveFromHandles(scalarA, handleA, handleB, PairMessages.ACK_INFO).joinToString("") { "%02x".format(it) })
        val ack = PairMessages.PairAck(handleA, handleB, nonceB, ok = true)
        val plain = PairMessages.encodeAck(ack, scalarA)
        assertEquals("RPA1|$handleA|$handleB|EBESExQVFhcYGRobHB0eHw|ok|16c3a2410c017f6d47fde64bcc81ca628bdc4683a4fa71abca25a8c3492023d5", plain)
        assertEquals(ack, PairMessages.decodeAck(plain, scalarB, handleB, expectedA = handleA))
        // Flip the result without the key: the tag no longer verifies.
        assertThrows(IllegalArgumentException::class.java) {
            PairMessages.decodeAck(plain.replace("|ok|", "|no|"), scalarB, handleB, expectedA = handleA)
        }
    }

    @Test
    fun inboxAnnouncement_matchesReference() {
        val a = PairMessages.InboxAnnouncement(handleA, handleB, inboxA, "https://relay.example.com")
        val plain = PairMessages.encodeInbox(a, scalarA)
        assertEquals("${a.body()}|f3f5122ada357225ab6649b1210b46500ccd58007bc8379c6e9c82c1ea2f7f64", plain)
        assertEquals(a, PairMessages.decodeInbox(plain, scalarB, handleB))
        assertThrows(IllegalArgumentException::class.java) {
            PairMessages.decodeInbox(plain.replace(inboxA, inboxB), scalarB, handleB)
        }
    }

    @Test
    fun unpairNotice_matchesReference() {
        assertEquals("4d464cb8d4488e7d6fc9c14167aa022842f3043111a4e0f4bab4e1ae97649676",
            PeerKey.deriveFromHandles(scalarA, handleA, handleB, PairMessages.UNPAIR_INFO).joinToString("") { "%02x".format(it) })
        val u = PairMessages.Unpair(handleA, handleB, 1791000000000L)
        val plain = PairMessages.encodeUnpair(u, scalarA)
        assertEquals("RPU1|$handleA|$handleB|1791000000000|6b4691e04a38ef8a9c2d28a6807d505fd39d97b118a18d70f7cf4e1650efcd30", plain)
        assertEquals(PairMessages.Kind.UNPAIR, PairMessages.kindOf(plain))
        assertEquals(u, PairMessages.decodeUnpair(plain, scalarB, handleB))
        // Move the time forward without the key: the tag no longer verifies.
        assertThrows(IllegalArgumentException::class.java) {
            PairMessages.decodeUnpair(plain.replace("|1791000000000|", "|1791000000001|"), scalarB, handleB)
        }
        // Addressed to someone else.
        assertThrows(IllegalArgumentException::class.java) { PairMessages.decodeUnpair(plain, scalarB, handleA) }
    }
}

class PairingFlowTests {
    private val provider = AgeProvider()

    private class Dev(p: AgeProvider) {
        val identity = p.generateIdentity()
        val handle = String(p.recipientToQr(p.recipientOf(identity)), Charsets.UTF_8)
        val scalar = p.scalarOf(identity)
        val inbox = InboxIds.generate()
    }

    @Test
    fun fullMutualPairing_overSealedMessages() {
        val a = Dev(provider); val b = Dev(provider)
        val shower = PairingFlow.Shower(); val scanner = PairingFlow.Scanner()
        val qrText = shower.newQr(a.handle, "Phone A", a.inbox, "", 1_000).encode()

        val qr = (ScannedQr.decode(qrText) as ScannedQr.V2).payload
        val (req, outstanding) = scanner.request(qr, b.handle, "Phone B", b.inbox, "", 2_000)
        val sealedReq = PairMessages.seal(provider, qr.recipientHandle, req.encode())

        val plainReq = PairMessages.open(provider, a.identity, sealedReq)
        assertEquals(PairMessages.Kind.PAIR_REQUEST, PairMessages.kindOf(plainReq))
        val pending = shower.accept(PairMessages.PairRequest.decode(plainReq), a.handle, 3_000)
        assertNotNull(pending)
        assertEquals(outstanding.code, pending!!.code)   // both screens show the same 8 digits

        val ackPlain = PairMessages.encodeAck(PairMessages.PairAck(a.handle, b.handle, pending.request.nonceB, true), a.scalar)
        val sealedAck = PairMessages.seal(provider, pending.request.handle, ackPlain)
        val ack = PairMessages.decodeAck(PairMessages.open(provider, b.identity, sealedAck), b.scalar, b.handle, qr.recipientHandle)
        val done = scanner.complete(ack, 4_000)
        assertNotNull(done)
        assertEquals(a.inbox, done!!.qr.inboxId)
    }

    @Test
    fun nonceIsSingleUse_andAnAttackersCodeDiffers() {
        val a = Dev(provider); val b = Dev(provider); val m = Dev(provider)
        val shower = PairingFlow.Shower()
        val qr = shower.newQr(a.handle, "A", a.inbox, "", 0)
        // The attacker saw the QR and races the real scanner.
        val (attackReq, _) = PairingFlow.Scanner().request(qr, m.handle, "M", m.inbox, "", 10)
        val (realReq, realOut) = PairingFlow.Scanner().request(qr, b.handle, "B", b.inbox, "", 20)
        val pending = shower.accept(attackReq, a.handle, 30)!!
        assertFalse(pending.code == realOut.code)          // A's screen won't match B's
        assertNull(shower.accept(realReq, a.handle, 40))   // and the nonce is spent
    }

    @Test
    fun staleOrRetiredQrIsRefused() {
        val a = Dev(provider); val b = Dev(provider)
        val shower = PairingFlow.Shower()
        val old = shower.newQr(a.handle, "A", a.inbox, "", 0)
        val (staleReq, _) = PairingFlow.Scanner().request(old, b.handle, "B", b.inbox, "", 0)
        assertNull(shower.accept(staleReq, a.handle, PairingFlow.VALIDITY_MS + 1))   // a screenshot, 5 minutes later
        shower.newQr(a.handle, "A", a.inbox, "", 100)
        assertNull(shower.accept(staleReq, a.handle, 200))                          // retired by a newer QR
        assertEquals(PairingFlow.VALIDITY_MS - 100, shower.remainingMs(200))
    }

    @Test
    fun ackFromSomeoneElseOrForAnotherRequestIsRefused() {
        val a = Dev(provider); val b = Dev(provider); val m = Dev(provider)
        val qr = PairingFlow.Shower().newQr(a.handle, "A", a.inbox, "", 0)
        val scanner = PairingFlow.Scanner()
        val (req, _) = scanner.request(qr, b.handle, "B", b.inbox, "", 0)
        // M forges an ACK claiming to be A: it can't produce A's tag.
        val forged = PairMessages.encodeAck(PairMessages.PairAck(m.handle, b.handle, req.nonceB, true), m.scalar)
            .replace(m.handle, a.handle)
        assertThrows(IllegalArgumentException::class.java) { PairMessages.decodeAck(forged, b.scalar, b.handle, a.handle) }
        // A genuine ACK with an unknown nonce answers nothing.
        val stray = PairMessages.PairAck(a.handle, b.handle, ByteArray(16), true)
        assertNull(scanner.complete(stray, 10))
    }
}
