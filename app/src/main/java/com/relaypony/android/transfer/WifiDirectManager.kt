package com.relaypony.android.transfer

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import com.relaypony.android.R

/**
 * Phase 7a: a thin wrapper over Android's Wi-Fi Direct (WifiP2p) stack, exposing just enough state
 * to prove a direct link can form between two devices with no shared Wi-Fi. Discovery, group
 * formation, and the group-owner address are surfaced as Compose state; the actual transfer over
 * the formed link is Phase 7b.
 *
 * Caller responsibilities: hold the location / NEARBY_WIFI_DEVICES permission before calling
 * [discover]/[connect] (the @SuppressLint below is sound only because of that), and call
 * [register]/[unregister] around the visible lifecycle so the broadcast receiver is live.
 */
class WifiDirectManager(context: Context) {

    private val appContext = context.applicationContext
    // Nullable on purpose: TVs, streaming boxes, and emulators often ship without the Wi-Fi P2P
    // service. A hard cast here would crash the whole app at TransferController construction.
    private val manager = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val channel = manager?.initialize(appContext, appContext.mainLooper, null)

    /** False when this device has no Wi-Fi Direct stack; callers hide the direct-link UI then. */
    val isSupported: Boolean = manager != null && channel != null

    val enabled = mutableStateOf(false)
    val p2pPeers = mutableStateListOf<WifiP2pDevice>()
    val connectionState = mutableStateOf(UiText(R.string.wd_not_connected))
    val groupOwnerAddress = mutableStateOf<String?>(null)
    val lastError = mutableStateOf<UiText?>(null)
    val isGroupOwner = mutableStateOf(false)

    /** Invoked when a group forms, with this device's role and the group-owner address. */
    var onConnected: ((isGroupOwner: Boolean, groupOwnerAddress: String?) -> Unit)? = null

    private val handler = Handler(appContext.mainLooper)

    private val intentFilter = IntentFilter().apply {
        addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
    }

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    enabled.value = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers()
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> requestConnectionInfo()
            }
        }
    }

    fun register() {
        if (!isSupported) return
        ContextCompat.registerReceiver(
            appContext, receiver, intentFilter, ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    fun unregister() {
        if (!isSupported) return
        runCatching { appContext.unregisterReceiver(receiver) }
    }

    /**
     * Start peer discovery.
     *
     * Several things this handles beyond a bare `discoverPeers()` call. First, a phone cannot run
     * its own hotspot and Wi-Fi Direct discovery at the same time — the radio can't be a SoftAP and
     * a P2P device at once, so the framework returns BUSY. That surfaced as a bare "framework busy,
     * try again" next to a device list that was never going to populate, with nothing pointing at
     * the cause. Second, discovery had no retry at all, so a transient BUSY was fatal. Third, on a
     * device with no working Wi-Fi Direct stack at all (TVs, streaming boxes, some emulators) or
     * with Wi-Fi turned off, fail fast with a message that actually points at the fix.
     */
    @SuppressLint("MissingPermission")
    fun discover() {
        val manager = manager
        val channel = channel
        if (manager == null || channel == null) {
            lastError.value = UiText(R.string.wd_discover_failed, UiText(R.string.wd_reason_unsupported))
            return
        }
        // Wi-Fi Direct rides on the same radio as regular Wi-Fi: if it's off (or the device has no
        // working Wi-Fi hardware at all), discoverPeers() below would just fail with an opaque
        // ERROR/"internal error" from the framework. Check first so the message actually points at
        // the fix instead of leaving a P2P error code to decode.
        val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifiManager?.isWifiEnabled == false) {
            lastError.value = UiText(R.string.wd_discover_failed, UiText(R.string.wd_reason_wifi_off))
            return
        }
        lastError.value = null
        if (isHotspotActive()) {
            lastError.value = UiText(R.string.wd_hotspot_conflict)
            return
        }
        discoverAttempt(0)
    }

    @SuppressLint("MissingPermission")
    private fun discoverAttempt(attempt: Int) {
        val manager = manager
        val channel = channel
        if (manager == null || channel == null) return
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {}
            override fun onFailure(reason: Int) {
                if (reason == WifiP2pManager.BUSY && attempt < MAX_DISCOVER_RETRIES) {
                    handler.postDelayed({ discoverAttempt(attempt + 1) }, DISCOVER_RETRY_MS)
                    return
                }
                lastError.value = if (reason == WifiP2pManager.BUSY && isHotspotActive()) {
                    UiText(R.string.wd_hotspot_conflict)
                } else {
                    UiText(R.string.wd_discover_failed, reasonText(reason))
                }
            }
        })
    }

    /**
     * Is this phone currently sharing its hotspot?
     *
     * `WifiManager.isWifiApEnabled` has been hidden since API 26, so we look at the interfaces
     * instead: tethering brings up an AP interface (`ap0`, `wlan1`, `swlan0`, …) carrying an IPv4
     * address, which is observable without any permission. A false negative just means the user
     * gets the old generic error, so this errs toward staying quiet.
     */
    private fun isHotspotActive(): Boolean = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().asSequence().any { nif ->
            nif.isUp && !nif.isLoopback &&
                HOTSPOT_IFACE_HINTS.any { hint -> nif.name.startsWith(hint) } &&
                nif.inetAddresses.asSequence().any { it is java.net.Inet4Address }
        }
    }.getOrDefault(false)

    @SuppressLint("MissingPermission")
    private fun requestPeers() {
        val manager = manager ?: return
        val channel = channel ?: return
        manager.requestPeers(channel) { list ->
            p2pPeers.clear()
            p2pPeers.addAll(list.deviceList)
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(device: WifiP2pDevice) {
        lastError.value = null
        connectAttempt(device, 0)
    }

    @SuppressLint("MissingPermission")
    private fun connectAttempt(device: WifiP2pDevice, attempt: Int) {
        val manager = manager
        val channel = channel
        if (manager == null || channel == null) {
            lastError.value = UiText(R.string.wd_connect_failed, UiText(R.string.wd_reason_unsupported))
            return
        }
        val config = WifiP2pConfig().apply { deviceAddress = device.deviceAddress }
        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { connectionState.value = UiText(R.string.wd_connecting, device.deviceName) }
            override fun onFailure(reason: Int) {
                if (reason == WifiP2pManager.BUSY && attempt < MAX_CONNECT_RETRIES) {
                    connectionState.value = UiText(R.string.wd_busy_retry, attempt + 1)
                    handler.postDelayed({ connectAttempt(device, attempt + 1) }, CONNECT_RETRY_MS)
                } else {
                    lastError.value = UiText(R.string.wd_connect_failed, reasonText(reason))
                }
            }
        })
    }

    private fun requestConnectionInfo() {
        val manager = manager ?: return
        val channel = channel ?: return
        manager.requestConnectionInfo(channel) { info: WifiP2pInfo ->
            if (info.groupFormed) {
                isGroupOwner.value = info.isGroupOwner
                groupOwnerAddress.value = info.groupOwnerAddress?.hostAddress
                connectionState.value =
                    if (info.isGroupOwner) UiText(R.string.wd_connected_owner)
                    else UiText(R.string.wd_connected_client, info.groupOwnerAddress?.hostAddress ?: "")
                onConnected?.invoke(info.isGroupOwner, info.groupOwnerAddress?.hostAddress)
            } else {
                connectionState.value = UiText(R.string.wd_not_connected)
                groupOwnerAddress.value = null
            }
        }
    }

    fun disconnect() {
        val manager = manager
        val channel = channel
        if (manager != null && channel != null) manager.removeGroup(channel, null)
        connectionState.value = UiText(R.string.wd_not_connected)
        groupOwnerAddress.value = null
    }

    private fun reasonText(reason: Int): UiText = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> UiText(R.string.wd_reason_unsupported)
        WifiP2pManager.BUSY -> UiText(R.string.wd_reason_busy)
        WifiP2pManager.ERROR -> UiText(R.string.wd_reason_internal)
        else -> UiText(R.string.wd_reason_other, reason)
    }

    companion object {
        private const val MAX_CONNECT_RETRIES = 3
        private const val CONNECT_RETRY_MS = 1500L
        private val HOTSPOT_IFACE_HINTS = listOf("ap0", "swlan", "wlan1", "softap")
        private const val MAX_DISCOVER_RETRIES = 2
        private const val DISCOVER_RETRY_MS = 1200L
    }
}
