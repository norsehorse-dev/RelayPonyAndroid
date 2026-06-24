package com.relaypony.session

import com.relaypony.crypto.CryptoProvider
import com.relaypony.crypto.Identity
import com.relaypony.crypto.Recipient
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
        onProgress: ((Long, Long) -> Unit)? = null,
        sink: FileSink,
    ): ReceiveResult {
        server.accept().use { socket ->
            BufferedInputStream(socket.getInputStream()).use { input ->
                return Session.receive(provider, identity, input, sink, onProgress)
            }
        }
    }

    /** Connect to [host]:[port] and send the given files. */
    fun sendTo(
        host: String,
        port: Int,
        provider: CryptoProvider,
        recipients: List<Recipient>,
        deviceName: String,
        senderRecipientHandle: String,
        files: List<OutgoingFile>,
        connectTimeoutMs: Int = 10_000,
        onProgress: ((Long, Long) -> Unit)? = null,
    ) {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            BufferedOutputStream(socket.getOutputStream()).use { out ->
                Session.send(provider, recipients, deviceName, senderRecipientHandle, files, out, onProgress)
            }
        }
    }
}
