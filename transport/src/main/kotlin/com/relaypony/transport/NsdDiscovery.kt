package com.relaypony.transport

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper

/**
 * mDNS / DNS-SD peer discovery and advertisement over `_relaypony._tcp`, via Android's
 * [NsdManager]. Android-only and not unit-testable on the JVM; exercised by the app harness on
 * real devices.
 *
 * Carries three TXT attributes so a discovered peer is immediately usable: "name" (display name),
 * "rcpt" (the sender's recipient handle, i.e. its `age1...` string), and "mw" (the highest wire
 * version this build speaks; wire v2 prep — absent on older builds and parsed as 1). In Phase 3 this means a
 * device can encrypt to a discovered peer without manual key entry. NOTE: trusting the recipient
 * from the discovery channel is trust-on-first-use; Phase 4's QR pairing replaces it with an
 * out-of-band, MITM-resistant key exchange.
 *
 * A Wi-Fi multicast lock is held while discovering, which many devices require to receive mDNS.
 */
class NsdDiscovery(context: Context) {

    data class Peer(
        val name: String,
        val host: String,
        val port: Int,
        val recipientHandle: String,
        /** Highest wire version the peer advertised ("mw"); 1 when absent (any older build). */
        val maxWire: Int = 1,
    )

    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val main = Handler(Looper.getMainLooper())
    private var multicastLock: WifiManager.MulticastLock? = null

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    fun advertise(serviceName: String, port: Int, deviceName: String, recipientHandle: String) {
        val info = NsdServiceInfo().apply {
            this.serviceName = serviceName
            serviceType = SERVICE_TYPE
            this.port = port
            setAttribute("name", deviceName)
            setAttribute("rcpt", recipientHandle)
            // The version this build actually speaks — honest by construction; flips with WIRE_VERSION.
            setAttribute(WireProtocol.MW_KEY, WireProtocol.MAX_WIRE_VERSION.toString())
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        registrationListener = listener
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun startDiscovery(onPeer: (Peer) -> Unit) {
        acquireMulticastLock()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceType.trimEnd('.') != SERVICE_TYPE.trimEnd('.')) return
                resolve(info, onPeer)
            }
        }
        discoveryListener = listener
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun resolve(info: NsdServiceInfo, onPeer: (Peer) -> Unit) {
        @Suppress("DEPRECATION")
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceResolved(resolved: NsdServiceInfo) {
                val attrs = resolved.attributes
                val rcpt = attrs["rcpt"]?.toString(Charsets.UTF_8) ?: return
                @Suppress("DEPRECATION")
                val host = resolved.host?.hostAddress ?: return
                val name = attrs["name"]?.toString(Charsets.UTF_8) ?: resolved.serviceName
                val maxWire = WireProtocol.parseMaxWire(attrs[WireProtocol.MW_KEY]?.toString(Charsets.UTF_8))
                main.post { onPeer(Peer(name, host, resolved.port, rcpt, maxWire)) }
            }
        })
    }

    /** Stop advertising only (used when pausing the receiver), leaving discovery untouched. */
    fun stopAdvertising() {
        registrationListener?.let { runCatching { nsd.unregisterService(it) } }
        registrationListener = null
    }

    fun stop() {
        discoveryListener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        registrationListener?.let { runCatching { nsd.unregisterService(it) } }
        discoveryListener = null
        registrationListener = null
        releaseMulticastLock()
    }

    private fun acquireMulticastLock() {
        if (multicastLock == null) {
            multicastLock = wifi.createMulticastLock("relaypony-nsd").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseMulticastLock() {
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
    }

    companion object {
        const val SERVICE_TYPE = "_relaypony._tcp."
    }
}
