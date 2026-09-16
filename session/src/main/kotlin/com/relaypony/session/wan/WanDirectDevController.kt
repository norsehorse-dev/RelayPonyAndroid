package com.relaypony.session.wan

import android.os.Handler
import android.os.Looper
import com.ponydirect.PonyDirectWan

/**
 * Drives the WAN-direct dev screen. Owns a PonyDirectWan wired to PonyPeerKeyProvider and the paste-
 * blob PasteSignaling, and reports state through plain callbacks (posted to the main thread). All
 * PonyDirect types stay inside this controller, so :app never has to depend on the PonyDirect lib.
 * Spike scaffolding: open a path to a paired peer over the internet with copy/paste signaling, then
 * send small messages over it.
 */
class WanDirectDevController(
    myScalar: ByteArray,
    private val myHandle: String,
    stunHost: String = "api.carrierpony.com",
    stunPort: Int = 3478,
) {
    private val main = Handler(Looper.getMainLooper())

    var onStatus: (String) -> Unit = {}
    var onOutgoing: (String) -> Unit = {}
    var onReceived: (String) -> Unit = {}

    private val signaling = PasteSignaling(myHandle) { blob -> main.post { onOutgoing(blob) } }

    private val wan = PonyDirectWan(
        PonyDirectWan.StunServer(stunHost, stunPort),
        PonyPeerKeyProvider(myScalar, myHandle),
        signaling,
    ).apply {
        delegate = object : PonyDirectWan.Delegate {
            override fun onPathState(peerID: String, state: PonyDirectWan.PathState) {
                main.post { onStatus(state.name) }
            }
            override fun onPayload(peerID: String, payload: ByteArray) {
                main.post { onReceived(String(payload)) }
            }
        }
    }

    /** The smaller handle offers, so exactly one side initiates (same rule CarrierPony uses). */
    fun openPath(peerHandle: String) {
        if (peerHandle.isEmpty()) return
        val role = if (myHandle < peerHandle) PonyDirectWan.Role.INITIATOR else PonyDirectWan.Role.RESPONDER
        wan.open(peerHandle, role)
    }

    fun importIncoming(blob: String) = signaling.importBlob(blob, wan)

    fun send(peerHandle: String, text: String) {
        if (peerHandle.isEmpty()) return
        wan.sendPayload(text.toByteArray(), peerHandle)
    }
}
