package com.relaypony.android.transfer

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
    private val manager = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private val channel = manager.initialize(appContext, appContext.mainLooper, null)

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
        ContextCompat.registerReceiver(
            appContext, receiver, intentFilter, ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    fun unregister() {
        runCatching { appContext.unregisterReceiver(receiver) }
    }

    @SuppressLint("MissingPermission")
    fun discover() {
        lastError.value = null
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {}
            override fun onFailure(reason: Int) { lastError.value = UiText(R.string.wd_discover_failed, reasonText(reason)) }
        })
    }

    @SuppressLint("MissingPermission")
    private fun requestPeers() {
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
        manager.removeGroup(channel, null)
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
    }
}
