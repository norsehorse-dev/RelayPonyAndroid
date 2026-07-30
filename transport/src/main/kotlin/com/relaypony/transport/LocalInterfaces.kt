package com.relaypony.transport

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * This device's own IPv4 network interfaces, as every platform's discovery code needs to see them.
 *
 * Shared deliberately. Both the phones and the desktop have been bitten by the same assumption —
 * that "the network" is one interface, the obvious one — and it is wrong on every machine that has
 * ethernet and Wi-Fi up, a VPN, `docker0`, `virbr0`, or an active hotspot. Discovery that binds to
 * one guessed interface fails silently and looks like the peer simply isn't there.
 *
 * Two rules encoded here:
 *
 *  * Never ask the OS "what is my address" (`InetAddress.getLocalHost()`). On Arch/Debian-family
 *    systems a `127.0.1.1 <hostname>` line in /etc/hosts makes it answer loopback.
 *  * Never filter to RFC1918 "site local". A phone hotspot can hand out carrier-grade-NAT space
 *    (100.64/10) and a direct cable leaves you on link-local (169.254/16). Both transfer fine.
 */
object LocalInterfaces {

    /** One usable IPv4 endpoint: where it lives, what it is, and how wide its subnet is. */
    data class Endpoint(
        val nif: NetworkInterface,
        val address: Inet4Address,
        val prefixLength: Short,
        /** Directed broadcast for this subnet, e.g. 192.168.43.255. Null on point-to-point links. */
        val broadcast: InetAddress?,
        /** Whether this link can carry multicast — mDNS needs it, the UDP beacon does not. */
        val supportsMulticast: Boolean,
    ) {
        val ifaceName: String get() = nif.name
        val ip: String get() = address.hostAddress ?: ""
        override fun toString(): String = "$ifaceName  $ip/$prefixLength"
    }

    /** An interface we walked past, and why — so "nobody found me" is always explainable. */
    data class Skipped(val ifaceName: String, val reason: String)

    /** Every up, non-loopback interface carrying an IPv4 address. [only] restricts by name. */
    fun endpoints(only: Set<String>? = null): List<Endpoint> {
        val out = ArrayList<Endpoint>()
        val ifaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return out
        for (nif in ifaces) {
            if (!isUsable(nif)) continue
            if (only != null && nif.name !in only) continue
            val multicast = runCatching { nif.supportsMulticast() }.getOrDefault(false)
            for (ia in runCatching { nif.interfaceAddresses }.getOrDefault(emptyList())) {
                val addr = ia.address
                if (addr !is Inet4Address) continue
                if (addr.isLoopbackAddress || addr.isAnyLocalAddress) continue
                out.add(Endpoint(nif, addr, ia.networkPrefixLength, ia.broadcast, multicast))
            }
        }
        return out
    }

    fun skipped(only: Set<String>? = null): List<Skipped> {
        val out = ArrayList<Skipped>()
        val ifaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return out
        for (nif in ifaces) {
            val reason = when {
                runCatching { nif.isLoopback }.getOrDefault(false) -> "loopback"
                !runCatching { nif.isUp }.getOrDefault(false) -> "down"
                only != null && nif.name !in only -> "excluded by configuration"
                runCatching { nif.interfaceAddresses }.getOrDefault(emptyList())
                    .none { it.address is Inet4Address } -> "no IPv4 address"
                else -> null
            }
            if (reason != null) out.add(Skipped(nif.name, reason))
        }
        return out
    }

    private fun isUsable(nif: NetworkInterface): Boolean = runCatching {
        nif.isUp && !nif.isLoopback
    }.getOrDefault(false)

    /** True if [peer] is inside [endpoint]'s subnet — i.e. reachable on that link without a router. */
    fun sameSubnet(peer: InetAddress, endpoint: Endpoint): Boolean {
        if (peer !is Inet4Address) return false
        return samePrefix(peer.address, endpoint.address.address, endpoint.prefixLength.toInt())
    }

    private fun samePrefix(a: ByteArray, b: ByteArray, bits: Int): Boolean {
        if (a.size != b.size || bits !in 0..(a.size * 8)) return false
        var remaining = bits
        var i = 0
        while (remaining >= 8) {
            if (a[i] != b[i]) return false
            i++; remaining -= 8
        }
        if (remaining == 0) return true
        val mask = (0xFF shl (8 - remaining)) and 0xFF
        return (a[i].toInt() and mask) == (b[i].toInt() and mask)
    }

    /**
     * The directed broadcast address for [endpoint], computed from the prefix when the OS didn't
     * supply one. Android in particular returns null here on some tethering interfaces, which is
     * exactly the case we most need broadcast to work on.
     */
    fun broadcastFor(endpoint: Endpoint): InetAddress? {
        endpoint.broadcast?.let { return it }
        val bits = endpoint.prefixLength.toInt()
        if (bits !in 1..31) return null          // /32 has no broadcast; /0 is not a real subnet
        val addr = endpoint.address.address.copyOf()
        for (i in addr.indices) {
            val bitsBefore = i * 8
            val hostBits = (bits - bitsBefore).coerceIn(0, 8)
            val hostMask = (0xFF ushr hostBits) and 0xFF
            addr[i] = (addr[i].toInt() or hostMask).toByte()
        }
        return runCatching { InetAddress.getByAddress(addr) }.getOrNull()
    }

    /**
     * Every host address in [endpoint]'s subnet, network and broadcast excluded, ourselves excluded.
     * Returns empty for subnets wider than [maxHosts] — sweeping a /16 is 65k connects and a bad
     * idea; the beacon is the mechanism for those.
     */
    fun hostsIn(endpoint: Endpoint, maxHosts: Int = 256): List<InetAddress> {
        val bits = endpoint.prefixLength.toInt()
        if (bits !in 1..30) return emptyList()
        val hostBits = 32 - bits
        val count = (1L shl hostBits) - 2                     // drop network + broadcast
        if (count <= 0 || count > maxHosts) return emptyList()

        val base = endpoint.address.address.copyOf()
        for (i in base.indices) {                             // mask down to the network address
            val bitsBefore = i * 8
            val keep = (bits - bitsBefore).coerceIn(0, 8)
            val netMask = ((0xFF shl (8 - keep)) and 0xFF)
            base[i] = (base[i].toInt() and netMask).toByte()
        }
        val network = base.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
        val mine = endpoint.address.address.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }

        val out = ArrayList<InetAddress>(count.toInt())
        for (h in 1..count) {
            val v = network + h
            if (v == mine) continue
            val bytes = byteArrayOf(
                ((v shr 24) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(),
                ((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte(),
            )
            runCatching { InetAddress.getByAddress(bytes) }.getOrNull()?.let { out.add(it) }
        }
        return out
    }

    /**
     * Default gateways, read from the kernel routing table on Linux and Android. On a phone hotspot
     * the gateway IS the phone, which makes this the single highest-value address to try first.
     * Returns empty on platforms where /proc isn't available; callers fall back to sweeping.
     */
    fun defaultGateways(): List<InetAddress> {
        val routes = runCatching { java.io.File("/proc/net/route").readLines() }.getOrNull()
            ?: return emptyList()
        val out = LinkedHashSet<InetAddress>()
        for (line in routes.drop(1)) {
            val f = line.split('\t', ' ').filter { it.isNotBlank() }
            if (f.size < 3) continue
            if (f[1] != "00000000") continue                  // destination 0.0.0.0 = default route
            val hex = f[2].toLongOrNull(16) ?: continue       // gateway, little-endian hex
            if (hex == 0L) continue
            val bytes = byteArrayOf(
                (hex and 0xFF).toByte(), ((hex shr 8) and 0xFF).toByte(),
                ((hex shr 16) and 0xFF).toByte(), ((hex shr 24) and 0xFF).toByte(),
            )
            runCatching { InetAddress.getByAddress(bytes) }.getOrNull()?.let { out.add(it) }
        }
        return out.toList()
    }
}
