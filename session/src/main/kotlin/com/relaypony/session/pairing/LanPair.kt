package com.relaypony.session.pairing

import com.relaypony.transport.WireProtocol
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The LAN half of pairing delivery (PROTOCOL_v3 section 4.5): one TCP connection to a peer's
 * transfer port, one [WireProtocol.PAIR] frame carrying a sealed PAIR_REQ or PAIR_ACK, then close.
 * The receiving side is [com.relaypony.session.SocketTransfer.acceptOne].
 *
 * Only call this for a peer that advertised `pr=1` or [com.relaypony.transport.Beacon.FLAG_PAIR].
 * The message is age-sealed to the intended handle, so delivering it to the wrong device (a forged
 * advertisement) reveals nothing; the relay copy still goes out either way.
 */
object LanPair {
    const val CONNECT_TIMEOUT_MS = 3_000

    /** Deliver [sealed]. Throws on any network failure; the caller treats LAN as best effort. */
    fun send(host: String, port: Int, sealed: ByteArray, connectTimeoutMs: Int = CONNECT_TIMEOUT_MS) {
        require(sealed.size <= WireProtocol.MAX_PAIR_PAYLOAD) { "pairing message too large: ${sealed.size}" }
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            socket.soTimeout = connectTimeoutMs
            val out = BufferedOutputStream(socket.getOutputStream())
            WireProtocol.writeFrame(out, WireProtocol.PAIR, sealed)
            out.flush()
            socket.shutdownOutput()
        }
    }
}
