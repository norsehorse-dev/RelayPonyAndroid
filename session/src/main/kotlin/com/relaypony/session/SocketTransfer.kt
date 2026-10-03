package com.relaypony.session

import com.relaypony.crypto.CryptoProvider
import com.relaypony.crypto.Identity
import com.relaypony.crypto.Recipient
import com.relaypony.transport.WireProtocol
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Runs a [Session] over a TCP socket. Pure java.net, so it is fully JVM-loopback-testable; the
 * mDNS discovery that finds the host/port lives separately in :transport (Android-only).
 *
 * Phase 3 is one file, one direction: the sender connects and pushes; the receiver accepts one
 * connection and pulls. Bidirectional ACKs and multi-connection handling come later.
 */
object SocketTransfer {

    /** Accept exactly one inbound connection on [server] and receive a session from it. */
    fun receiveOnceFrom(
        server: ServerSocket,
        provider: CryptoProvider,
        identity: Identity,
        deviceName: String = "",
        recipientHandle: String = "",
        onProgress: ((Long, Long) -> Unit)? = null,
        limits: TransferLimits = TransferLimits.DEFAULT,
        sink: FileSink,
    ): ReceiveResult {
        server.accept().use { socket ->
            val input = BufferedInputStream(socket.getInputStream())
            val reverseOut = BufferedOutputStream(socket.getOutputStream())
            return Session.receive(
                provider, identity, input, sink,
                reverseOut = reverseOut,
                deviceName = deviceName,
                recipientHandle = recipientHandle,
                onProgress = onProgress,
                limits = limits,
            )
        }
    }

    /**
     * Accept one inbound connection on [server] and handle whichever kind it is (4.0). A connection
     * whose first frame is [WireProtocol.PAIR] carries one sealed pairing message: it is handed to
     * [onPair] and the connection closes, returning null. Anything else is a transfer session,
     * received exactly as [receiveOnceFrom] does.
     *
     * The first frame must arrive within [firstFrameTimeoutMs]; after that the session runs with no
     * read timeout, as in 3.x. Without it, one idle connection would hold the single accept loop.
     * A connection that stays silent that long is closed and returns null, like one that connects
     * and leaves: neither is a failed transfer worth reporting.
     */
    fun acceptOne(
        server: ServerSocket,
        provider: CryptoProvider,
        identity: Identity,
        onPair: (ByteArray) -> Unit,
        deviceName: String = "",
        recipientHandle: String = "",
        onProgress: ((Long, Long) -> Unit)? = null,
        limits: TransferLimits = TransferLimits.DEFAULT,
        firstFrameTimeoutMs: Int = FIRST_FRAME_TIMEOUT_MS,
        /** 4.0: who to take (section 9.2); see [Session.receive]. */
        gate: ((WireProtocol.Hello) -> String?)? = null,
        sink: FileSink,
    ): ReceiveResult? {
        server.accept().use { socket ->
            socket.soTimeout = firstFrameTimeoutMs
            val input = BufferedInputStream(socket.getInputStream())
            input.mark(1)
            val type = try {
                input.read()
            } catch (e: java.net.SocketTimeoutException) {
                return null                                         // never said anything
            }
            if (type < 0) return null                               // connected and left
            input.reset()
            if (type.toByte() == WireProtocol.PAIR) {
                // A bad PAIR frame (oversized, truncated, too slow) is dropped quietly: pairing has
                // the relay copy to fall back on, and it is no reason to report a failed transfer.
                val frame = runCatching { WireProtocol.readFrame(input) }.getOrNull() ?: return null
                onPair(frame.payload)
                return null
            }
            socket.soTimeout = 0
            val reverseOut = BufferedOutputStream(socket.getOutputStream())
            return Session.receive(
                provider, identity, input, sink,
                reverseOut = reverseOut,
                deviceName = deviceName,
                recipientHandle = recipientHandle,
                onProgress = onProgress,
                limits = limits,
                gate = gate,
            )
        }
    }

    const val FIRST_FRAME_TIMEOUT_MS = 30_000

    /** Connect to [host]:[port] and send the given files. */
    fun sendTo(
        host: String,
        port: Int,
        provider: CryptoProvider,
        recipients: List<Recipient>,
        deviceName: String,
        senderRecipientHandle: String,
        files: List<OutgoingFile>,
        peerMaxWire: Int = 1,
        connectTimeoutMs: Int = 10_000,
        /** 4.0: builds the HELLO sender tag tail (section 9.1); see [HelloAuth.signer]. */
        helloAuth: ((ByteArray) -> ByteArray)? = null,
        /** Handed the socket before connecting, so the caller can close it to stop the send. */
        onSocket: ((Socket) -> Unit)? = null,
        // Last, so existing callers can keep passing progress as a trailing lambda.
        onProgress: ((Long, Long) -> Unit)? = null,
    ) {
        Socket().use { socket ->
            onSocket?.invoke(socket)
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            val reverseIn = BufferedInputStream(socket.getInputStream())
            BufferedOutputStream(socket.getOutputStream()).use { out ->
                Session.send(
                    provider, recipients, deviceName, senderRecipientHandle, files, out,
                    peerMaxWire = peerMaxWire,
                    reverseIn = reverseIn,
                    helloAuth = helloAuth,
                    onProgress = onProgress,
                )
            }
        }
    }
}
