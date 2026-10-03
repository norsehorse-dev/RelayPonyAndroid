package com.relaypony.android.transfer

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.MimeTypeMap
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.relaypony.android.MainActivity
import com.relaypony.android.R
import com.relaypony.crypto.AgeProvider
import com.relaypony.session.FanOut
import com.relaypony.session.HelloAuth
import com.relaypony.session.RefusedException
import com.relaypony.transport.WireProtocol
import com.relaypony.session.FileNames
import com.relaypony.session.TransferLimits
import com.relaypony.session.IdentityBackup
import com.relaypony.session.OutgoingFile
import com.relaypony.session.Ident
import com.relaypony.session.SocketTransfer
import com.relaypony.session.WifiIdent
import com.relaypony.session.inbox.ReceivedFile
import com.relaypony.session.pairing.Pairing
import com.relaypony.session.pairing.Sas
import java.util.Locale
import com.relaypony.session.pairing.QrPayload
import com.relaypony.transport.Beacon
import com.relaypony.session.wan.RelayConfig
import com.relaypony.session.wan.RelayUrls
import com.relaypony.session.wan.RelayClient
import com.relaypony.session.pairing.PeerRoute
import com.relaypony.session.pairing.PakeDetails
import com.relaypony.pake.WordCodePairing
import com.relaypony.session.pairing.LanPair
import com.relaypony.session.pairing.PairingService
import com.relaypony.session.pairing.InboxIds
import com.relaypony.session.wan.WanTransfer
import com.relaypony.session.wan.WanStatus
import com.relaypony.session.wan.WanStatusKind
import com.relaypony.transport.BeaconDiscovery
import com.relaypony.transport.LocalInterfaces
import com.relaypony.transport.NsdDiscovery
import java.io.ByteArrayInputStream
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Phase 6 harness wiring. Adds parallel group send: several paired peers can be selected and the
 * same files are sent to all of them at once, each over its own connection and encrypted to its
 * own key. Per-peer outcomes land in [sendStatus] as each transfer finishes, so a slow or
 * unreachable peer never blocks the rest.
 */
class TransferController(context: Context) {

    private val appContext = context.applicationContext
    private val provider = AgeProvider()
    private val identityStore = KeystoreIdentityStore(appContext)
    private val identity = identityStore.loadOrCreate(provider)
    private val myRecipient = provider.recipientOf(identity)
    private val trustStore = PrefsTrustStore(appContext)
    private val inboxStore = PrefsInboxStore(appContext)
    private val settings = appContext.getSharedPreferences("relaypony_settings", Context.MODE_PRIVATE)

    /** Peers seen sending sealed (4.0) WAN signaling. They get sealed blobs only, and plaintext
     *  blobs claiming to be from them are dropped. Kept apart from the trust store so the pinned
     *  model and its backups don't change. */
    private val sealedPeers = appContext.getSharedPreferences("relaypony_sealed_peers", Context.MODE_PRIVATE)
    private fun isSealedPeer(handle: String): Boolean = sealedPeers.getBoolean(handle, false)
    private fun markSealedPeer(handle: String) { sealedPeers.edit().putBoolean(handle, true).apply() }

    /** WAN-direct relay base URL (self-host override), persisted across launches. */
    var relayServer: String
        get() = settings.getString("relay_server", null)?.takeIf { it.isNotEmpty() } ?: "https://relaypony.app"
        set(value) {
            val v = value.trim()
            settings.edit().putString("relay_server", v).apply()
            RelayConfig.baseUrl = if (v.isEmpty()) "https://relaypony.app" else v
            // This device's inbox now lives on a different relay: re-check what it supports and
            // tell paired devices again on next contact (PROTOCOL_v3.md section 5.3).
            relayClients.clear()
            relayHasMailboxes = null
            inboxUsers.edit().clear().apply()
            checkRelayFeatures()
        }

    init {
        val saved = settings.getString("relay_server", null)
        if (!saved.isNullOrEmpty()) RelayConfig.baseUrl = saved
    }

    /** This device's recipient handle (age1 string), advertised over mDNS and shown in its QR. */
    val myHandle: String = String(provider.recipientToQr(myRecipient), Charsets.UTF_8)
    /** This device's raw age X25519 scalar, for the WAN-direct dev spike. */
    val myScalar: ByteArray = provider.scalarOf(identity)
    val deviceName: String = Build.MODEL ?: "Android"

    private val discovery = NsdDiscovery(appContext)

    /**
     * Broadcast discovery, running alongside mDNS rather than instead of it.
     *
     * NsdManager follows the process's *default* network. While this phone shares its hotspot the
     * default network is mobile data, so mDNS advertisements go out over cellular and nothing on
     * the tethered subnet ever hears them — which is exactly why a laptop on the hotspot could
     * never find the phone. The beacon sends from a socket bound to each local interface address
     * in turn, so it speaks on the subnet actually being shared.
     */
    private val beacon = BeaconDiscovery()
    private val wifiManager =
        appContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
    private var beaconLock: android.net.wifi.WifiManager.MulticastLock? = null

    val wifiDirect = WifiDirectManager(appContext)
    private val main = Handler(Looper.getMainLooper())

    val status = mutableStateOf(idleStatusText())
    val peers = mutableStateListOf<NsdDiscovery.Peer>()
    val pendingShare = mutableStateListOf<OutgoingFile>()
    val inbox = mutableStateListOf<ReceivedFile>()

    /** Per-peer send outcome, keyed by "host:port" (e.g. "Sending…", "Sent", "Failed: …"). */
    val sendStatus = mutableStateMapOf<String, String>()

    /** Live status of the current Wi-Fi Direct transfer. */
    val wifiTransferStatus = mutableStateOf(UiText(R.string.st_idle))

    /** Per-peer "is a send in flight" flag, parallel to sendStatus. Drives the progress UI. */
    val sendInProgress = mutableStateMapOf<String, Boolean>()

    /** Per-peer send progress in 0f..1f, parallel to sendStatus. Drives the determinate bar. */
    val sendProgress = mutableStateMapOf<String, Float>()

    /** The port we ended up listening on, shown on the Receive screen so it can be typed elsewhere. */
    val listenPort = mutableStateOf(0)

    /** "192.168.1.24:45789" per interface — the addresses another device can reach this one at. */
    val reachableAddresses = mutableStateListOf<String>()

    /** True while a file is actively being received (drives the receive progress card). */
    val receiveInProgress = mutableStateOf(false)

    /** Current receive progress in 0f..1f. */
    val receiveProgress = mutableStateOf(0f)

    /** Classifies the last status update so the UI never parses display text. */
    val lastStatusKind = mutableStateOf(StatusKind.OTHER)

    /** Result of the last openFile() attempt, shown inline on the Inbox screen. Kept separate from
     *  [status] (which nothing renders on that tab) so a failed open is never a silent no-op. */
    val openError = mutableStateOf<String?>(null)

    enum class StatusKind { OTHER, RECEIVED }

    private fun localizedContext(): Context {
        val tag = languageCode.value
        if (tag.isEmpty()) return appContext
        val config = Configuration(appContext.resources.configuration)
        config.setLocale(Locale.forLanguageTag(tag))
        return appContext.createConfigurationContext(config)
    }

    private fun str(id: Int, vararg args: Any?): String = localizedContext().getString(id, *args)

    /** Map a session-layer [WanStatus] to a localized string in the current in-app language. */
    private fun wanStatusText(st: WanStatus): String = when (st.kind) {
        WanStatusKind.READY -> str(R.string.wan_ready)
        WanStatusKind.IN_PROGRESS -> str(R.string.wan_in_progress)
        WanStatusKind.ADD_FILES -> str(R.string.wan_add_files)
        WanStatusKind.PREPARING -> str(R.string.wan_preparing)
        WanStatusKind.PREPARE_FAILED -> str(R.string.wan_prepare_failed, st.arg ?: str(R.string.wan_unknown_error))
        WanStatusKind.CONNECTING -> str(R.string.wan_connecting)
        WanStatusKind.SENDING -> str(R.string.wan_sending)
        WanStatusKind.SENDING_RELAY -> str(R.string.wan_sending_relay)
        WanStatusKind.SENT -> str(R.string.wan_sent)
        WanStatusKind.SEND_FAILED -> str(R.string.wan_send_failed)
        WanStatusKind.RECEIVING -> str(R.string.wan_receiving)
        WanStatusKind.RECEIVING_RELAY -> str(R.string.wan_receiving_relay)
        WanStatusKind.RECEIVED -> str(R.string.wan_received, st.count, st.arg ?: "")
        WanStatusKind.RECEIVE_FAILED -> str(R.string.wan_receive_failed, st.arg ?: str(R.string.wan_unknown_error))
        WanStatusKind.CANCELLED -> str(R.string.wan_cancelled)
    }

    /** The idle status string in the persisted in-app language, read directly from settings so it does
     *  not depend on [languageCode] (declared later) and does not leak the process default locale. */
    private fun idleStatusText(): String {
        val tag = settings.getString(KEY_LANG, "en") ?: "en"
        if (tag.isEmpty()) return appContext.getString(R.string.st_idle)
        val cfg = Configuration(appContext.resources.configuration)
        cfg.setLocale(Locale.forLanguageTag(tag))
        return appContext.createConfigurationContext(cfg).getString(R.string.st_idle)
    }

    private fun setStatus(text: String, kind: StatusKind = StatusKind.OTHER) {
        status.value = text
        lastStatusKind.value = kind
        if (kind == StatusKind.RECEIVED) showNotice(text)
    }

    /**
     * A one-line message for the snackbar: the outcome of something the user did (a backup, a
     * relay change) or something that just arrived. Cleared by the UI once shown.
     */
    val notice = mutableStateOf<String?>(null)

    fun showNotice(text: String) {
        notice.value = text
    }

    /** When on, received files are also copied to public Downloads. Persisted. */
    val autoSave = mutableStateOf(settings.getBoolean(KEY_AUTOSAVE, false))

    /** First-run onboarding gate. True until the user finishes the intro at least once. */
    val showOnboarding = mutableStateOf(!settings.getBoolean(KEY_ONBOARDED, false))

    /** Selected UI language code (BCP-47 tag). Persisted; applied at the Compose layer without
     *  recreating the Activity, so switching is flicker-free. */
    val languageCode = mutableStateOf(settings.getString(KEY_LANG, "en") ?: "en")

    enum class ThemeMode { SYSTEM, LIGHT, DARK }

    /** UI theme preference. Persisted. SYSTEM follows the device dark-mode setting. */
    val themeMode = mutableStateOf(loadThemeMode())

    private fun loadThemeMode(): ThemeMode =
        runCatching { ThemeMode.valueOf(settings.getString(KEY_THEME, ThemeMode.SYSTEM.name)!!) }
            .getOrDefault(ThemeMode.SYSTEM)

    fun setThemeMode(mode: ThemeMode) {
        themeMode.value = mode
        settings.edit().putString(KEY_THEME, mode.name).apply()
    }

    /** Bumped whenever the trust store changes, so the UI re-classifies peers. */
    val trustRevision = mutableIntStateOf(0)

    /** True while an identity export/import is running (disables the buttons in Settings). */
    val identityBusy = mutableStateOf(false)

    /** Per-peer WAN send status, keyed by the peer's age handle. */
    val wanSendStatus = mutableStateMapOf<String, String>()
    /** Peers a WAN send is currently in flight to. */
    val wanSending = mutableStateOf<Set<String>>(emptySet())
    /** True while WAN receive is armed (the Receive tab is open). */
    val wanReceiveActive = mutableStateOf(false)
    /** Short status line for the WAN receive state. */
    val wanReceiveStatus = mutableStateOf("")
    /** True while a transfer is arriving over the internet. */
    val wanReceiving = mutableStateOf(false)

    /** WAN-direct transfer engine (paired devices not on the same LAN). Assigned in init. */
    val wan: WanTransfer

    private var serverSocket: ServerSocket? = null

    /** Whether the LAN listener is currently accepting connections (drives the Receive UI). */
    val isReceiving = mutableStateOf(false)

    /** User intent to receive. False after an explicit Stop, so re-entering the tab won't auto-start. */
    val wantsReceiving = mutableStateOf(true)

    @Volatile
    private var wifiArmed = false
    private var wifiAsSender = false

    init {
        refreshInbox()
        refreshShareShortcuts()
        // Folder zips left behind by a process that died mid-send.
        runCatching { folderZipDir().deleteRecursively() }
        wifiDirect.onConnected = { isGroupOwner, goAddress -> onWifiConnected(isGroupOwner, goAddress) }
        wan = WanTransfer(
            provider = provider,
            identity = identity,
            myScalar = myScalar,
            myHandle = myHandle,
            deviceName = deviceName,
            saveDir = { File(appContext.filesDir, "inbox") },
            isPinned = { trustStore.isPinned(it) },
            limits = { receiveLimits() },
            isSealedPeer = { isSealedPeer(it) },
            markSealedPeer = { markSealedPeer(it) },
        ).apply {
            onSendStatus = { peer, st ->
                wanSendStatus[peer] = wanStatusText(st)
                onWanLegStatus(peer, st)
            }
            onSendProgress = { peer, sent, total ->
                updateLeg(peer) { if (it.route != SendRoute.NEARBY && it.active) it.copy(progress = sent.toFloat() / total) else it }
            }
            onSendingChanged = { set -> wanSending.value = set }
            onReceiveStatus = { st ->
                wanReceiveStatus.value = wanStatusText(st)
                wanReceiving.value = st.kind == WanStatusKind.RECEIVING || st.kind == WanStatusKind.RECEIVING_RELAY
            }
            onReceived = { batch -> recordWanReceived(batch) }
        }
    }

    // ---- 4.0: private inboxes and mutual pairing (PROTOCOL_v3.md sections 4 and 5) ----

    /** Where each paired device can be reached on the relay. */
    private val peerRoutes = PrefsPeerRouteStore(appContext)

    /** Peers seen reaching this device through its inbox, so they need no more announcements. */
    private val inboxUsers = appContext.getSharedPreferences("relaypony_inbox_users", Context.MODE_PRIVATE)

    /** This device's private relay inbox, created on first launch. Shared only during pairing. */
    val myInboxId: String
        get() = settings.getString("inbox_id", null)?.takeIf { InboxIds.isValid(it) }
            ?: InboxIds.generate().also { settings.edit().putString("inbox_id", it).apply() }

    /** Give this device a new inbox (Advanced, Identity). Paired devices learn it on next contact. */
    fun rotateInbox() {
        settings.edit().putString("inbox_id", InboxIds.generate()).apply()
        inboxUsers.edit().clear().apply()
    }

    /** This device's relay, normalized ("" for the default). */
    val myRelay: String get() = RelayUrls.normalize(relayServer)

    private val relayClients = java.util.concurrent.ConcurrentHashMap<String, RelayClient>()
    private fun relayClient(normalized: String): RelayClient =
        relayClients.getOrPut(normalized) { RelayClient(RelayUrls.base(normalized)) }

    /** Whether this device's relay has mailboxes (relay 2.0). Null until the first check answers. */
    @Volatile var relayHasMailboxes: Boolean? = null
        private set

    private fun checkRelayFeatures() {
        thread(name = "relaypony-relay-info") {
            val info = relayClient(myRelay).info()
            relayHasMailboxes = info?.hasMailboxes ?: false
        }
    }

    val pairing: PairingService = PairingService(
        provider = provider,
        identity = identity,
        myScalar = myScalar,
        myHandle = myHandle,
        myName = { deviceName },
        myInbox = { myInboxId },
        myRelay = { myRelay },
        trust = trustStore,
        routes = peerRoutes,
        mailer = { peer, relay, inbox, payload ->
            // Both paths, always: the relay reaches a peer anywhere, the LAN reaches one with no
            // internet at all. The receiver handles whichever copy lands first and ignores the other.
            thread(name = "relaypony-pair-send") {
                runCatching { relayClient(relay).mboxSend(inbox, payload) }
            }
            deliverPairOverLan(peer, payload)
        },
    ).apply {
        onEvent = { ev -> pair.onEvent(ev) }
    }

    /** The pair sheet: QR, scan, word codes, the code comparison and pair-and-send. */
    val pair: PairingController = PairingController(
        service = pairing,
        newWordCode = {
            WordCodePairing(PakeDetails(myHandle, deviceName, myInboxId, myRelay), { relay -> relayClient(relay) })
        },
        relayHasMailboxes = { relayHasMailboxes },
        myHandle = myHandle,
        setListening = { on -> setPairSheetOpen(on) },
        onPinned = { handle, name ->
            requests.removeAll { it.handle == handle }
            trustRevision.intValue++
            refreshShareShortcuts()
            setStatus(str(R.string.st_paired_with, name))
        },
        pinWordCodePeer = { peer ->
            trustStore.pin(peer.handle, peer.name)
            peerRoutes.put(peer.handle, PeerRoute(peer.inboxId, peer.relay))
        },
        onUnpinned = { handle -> afterUnpin(handle) },
        main = main,
    ).apply {
        onPairAndSend = { handle -> sendToDevices(listOf(handle)) }
    }

    init {
        wan.hooks = object : WanTransfer.InboxHooks {
            override fun myInbox(): String? = if (relayHasMailboxes == true) myInboxId else null
            override fun myRelayClient(): RelayClient = relayClient(myRelay)
            override fun route(peer: String): PeerRoute? = peerRoutes.get(peer)
            override fun clientFor(relay: String): RelayClient = relayClient(relay)
            override fun announcement(peer: String): ByteArray? =
                if (relayHasMailboxes == true && trustStore.isPinned(peer)) pairing.inboxAnnouncement(peer) else null
            override fun peerUsesMyInbox(peer: String): Boolean = inboxUsers.getBoolean(peer, false)
            override fun markPeerUsesMyInbox(peer: String) { inboxUsers.edit().putBoolean(peer, true).apply() }
        }
        wan.onInboxMessage = { plain -> pairing.onSealedPlain(plain) }
        checkRelayFeatures()
    }

    // ---- 4.0: paired-only receive (PROTOCOL_v3.md section 9, plan section 9.2) ----

    /** Advanced: take LAN transfers from devices that aren't paired. Off unless the user turns it on. */
    val acceptUnpaired = mutableStateOf(settings.getBoolean(KEY_ACCEPT_UNPAIRED, false))

    fun setAcceptUnpaired(on: Boolean) {
        acceptUnpaired.value = on
        settings.edit().putBoolean(KEY_ACCEPT_UNPAIRED, on).apply()
    }

    /** Paired devices seen sending a tagged HELLO. From then on an untagged one from them is a downgrade. */
    private val taggedPeers = appContext.getSharedPreferences("relaypony_tagged_peers", Context.MODE_PRIVATE)

    /** Tags already accepted, so a captured HELLO can't be used twice. Hex tag to time seen. */
    private val seenTags = HashMap<String, Long>()

    private fun firstUseOf(tag: ByteArray, nowMs: Long): Boolean = synchronized(seenTags) {
        seenTags.entries.removeAll { nowMs - it.value > TAG_MEMORY_MS }
        val key = tag.joinToString("") { "%02x".format(it) }
        if (seenTags.containsKey(key)) false else { seenTags[key] = nowMs; true }
    }

    /**
     * Whether to take a LAN transfer from the sender of [hello] (section 9.2). Null accepts; a
     * reason refuses it, and the sender is listed under Requests. Runs on the accept thread.
     */
    private fun receiveGate(hello: WireProtocol.Hello): String? {
        val from = hello.recipientHandle
        val pinned = trustStore.isPinned(from)
        val auth = hello.auth
        val now = System.currentTimeMillis()
        if (pinned && auth != null) {
            if (HelloAuth.verify(myScalar, myHandle, from, auth, now) && firstUseOf(auth.tag, now)) {
                if (!taggedPeers.getBoolean(from, false)) taggedPeers.edit().putBoolean(from, true).apply()
                return null
            }
            return refuse(hello)
        }
        // A paired 3.x device sends no tag. Once a device has tagged, an untagged HELLO claiming
        // to be it is refused.
        if (pinned && !taggedPeers.getBoolean(from, false)) return null
        if (!pinned && acceptUnpaired.value) return null
        return refuse(hello)
    }

    private fun refuse(hello: WireProtocol.Hello): String {
        val name = hello.deviceName.take(64)
        val handle = hello.recipientHandle
        main.post { noteRequest(handle, name) }
        return "unpaired"
    }

    /** The label for received files: the name this device paired with, never the sender's own. */
    private fun senderLabel(handle: String, claimedName: String): String =
        trustStore.get(handle)?.name ?: str(R.string.rec_unpaired_label, claimedName)

    /** A device that tried to send here and was refused (plan section 9.3). Name and handle are its own claim. */
    data class SendRequest(val handle: String, val name: String, val atMs: Long)

    /** Recent refused senders, newest first, for the Requests card in Received. */
    val requests = mutableStateListOf<SendRequest>()

    private fun noteRequest(handle: String, name: String) {
        requests.removeAll { it.handle == handle }
        requests.add(0, SendRequest(handle, name, System.currentTimeMillis()))
        while (requests.size > MAX_REQUESTS) requests.removeAt(requests.lastIndex)
    }

    fun dismissRequest(request: SendRequest) {
        requests.remove(request)
    }

    /**
     * Unpair [handle] (PROTOCOL_v3.md section 4.7). The notice goes out best effort by every path:
     * the peer's inbox, its handle queue on this device's relay, and the LAN when it is discovered.
     * Then the pin and route are dropped here.
     */
    fun unpair(handle: String) {
        val notice = runCatching { pairing.unpairNotice(handle) }.getOrNull()
        val route = peerRoutes.get(handle)
        pairing.forget(handle)
        if (notice != null) {
            if (route != null) {
                thread(name = "relaypony-unpair") { runCatching { relayClient(route.relay).mboxSend(route.inboxId, notice) } }
            }
            // The route is gone now, so this one goes to the handle queue.
            wan.sendSealed(handle, notice)
            deliverPairOverLan(handle, notice)
        }
        afterUnpin(handle)
    }

    /** Clean-up shared by unpairing here and being unpaired by the other device. */
    private fun afterUnpin(handle: String) {
        inboxUsers.edit().remove(handle).apply()
        runCatching { ShortcutManagerCompat.removeLongLivedShortcuts(appContext, listOf(SHORTCUT_PREFIX + handle)) }
        refreshShareShortcuts()
        if (preselectHandle.value == handle) preselectHandle.value = null
        trustRevision.intValue++
    }

    /** Whether opening the pair sheet started the LAN listener or mDNS browsing, to undo on close. */
    private var pairStartedListener = false
    private var pairStartedBrowsing = false
    private var wantsReceivingBeforePair = true

    /**
     * Pairing started or finished (driven by [PairingController]). While it runs the inbox is
     * polled, and the LAN listener and discovery run even if the user paused receiving, so the
     * PAIR_REQ and PAIR_ACK can travel over Wi-Fi with no internet. Finishing puts both back the
     * way it found them.
     */
    private fun setPairSheetOpen(open: Boolean) {
        wan.holdPolling(open)
        if (open) {
            if (serverSocket == null) {
                wantsReceivingBeforePair = wantsReceiving.value
                startReceiving()
                pairStartedListener = true
            }
            if (!discovery.isDiscovering) {
                discovery.startDiscovery { peer -> addPeer(peer) }
                pairStartedBrowsing = true
            }
            acquireBeaconLock()
            beacon.listen(myHandle, ::addBeaconPeer)
            probeForPeers()
        } else {
            if (pairStartedListener) {
                stopReceiving()
                wantsReceiving.value = wantsReceivingBeforePair
                pairStartedListener = false
            }
            if (pairStartedBrowsing) {
                runCatching { discovery.stopDiscovery() }
                pairStartedBrowsing = false
            }
        }
    }

    /**
     * Send a sealed pairing message straight to [peer] over the LAN when it is discovered and
     * advertises `pr=1` (PROTOCOL_v3 section 4.5). If it isn't in the list yet (the QR was scanned
     * a moment after the sheet opened), probe once and try again. Best effort: the relay copy goes
     * out regardless.
     */
    private fun deliverPairOverLan(peer: String, payload: ByteArray) {
        thread(name = "relaypony-pair-lan") {
            var target = peers.toList().firstOrNull { it.recipientHandle == peer && it.pairCapable }
            if (target == null) {
                runCatching {
                    beacon.probe(1500) { p ->
                        main.post { addBeaconPeer(p) }
                        if (target == null && p.recipientHandle == peer && p.pairCapable) {
                            target = NsdDiscovery.Peer(p.name, p.host, p.port, p.recipientHandle, p.maxWire, p.pairCapable)
                        }
                    }
                }
            }
            target?.let { t -> runCatching { LanPair.send(t.host, t.port, payload) } }
        }
    }

    /** A4: a handle a Direct Share target asked us to pre-select on the Send screen, or null.
     *  The Send-to sheet ticks it when it opens. */
    val preselectHandle = mutableStateOf<String?>(null)

    /** Called from MainActivity when the app was opened via a Direct Share target. Remembers the
     *  device to pre-check and makes sure discovery is running so it can actually be found. */
    fun preselectForSend(handle: String) {
        preselectHandle.value = handle
        if (trustStore.isPinned(handle)) sendToPreselect.value = setOf(handle)
    }

    /** A4: publish the paired devices as Direct Share targets, newest pins first, capped to the
     *  launcher's per-activity limit. Called on launch and whenever a device is pinned or unpaired
     *  (unpairing also removes that device's cached long-lived shortcut). All wrapped in
     *  runCatching because shortcut publishing is a best-effort convenience, never load-bearing. */
    private fun refreshShareShortcuts() {
        runCatching {
            val max = ShortcutManagerCompat.getMaxShortcutCountPerActivity(appContext).coerceAtLeast(1)
            val shortcuts = trustStore.all()
                .sortedByDescending { it.pinnedAtEpochMs }
                .take(max)
                .map { device ->
                    val label = device.name.ifBlank { appContext.getString(R.string.app_name) }
                    ShortcutInfoCompat.Builder(appContext, SHORTCUT_PREFIX + device.recipientHandle)
                        .setShortLabel(label)
                        .setLongLabel(label)
                        .setIcon(monogramIcon(label))
                        .setCategories(setOf(SHARE_CATEGORY))
                        .setLongLived(true)
                        .setIntent(Intent(appContext, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
                        .build()
                }
            ShortcutManagerCompat.setDynamicShortcuts(appContext, shortcuts)
        }
    }

    /** A simple round monogram so the Direct Share faces are distinguishable. Falls back to the
     *  launcher icon if anything about drawing fails. */
    private fun monogramIcon(name: String): IconCompat = runCatching {
        val size = 192
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawCircle(
            size / 2f, size / 2f, size / 2f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x5A, 0x4F, 0xE0) },
        )
        val initials = name.trim().split(Regex("\\s+"))
            .mapNotNull { it.firstOrNull()?.uppercaseChar() }
            .take(2).joinToString("").ifEmpty { "?" }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = size * 0.42f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val baseline = size / 2f - (text.descent() + text.ascent()) / 2f
        canvas.drawText(initials, size / 2f, baseline, text)
        IconCompat.createWithBitmap(bmp)
    }.getOrElse { IconCompat.createWithResource(appContext, R.mipmap.ic_launcher) }

    private fun refreshInbox() {
        inbox.clear()
        inbox.addAll(inboxStore.all())
    }

    fun peerKey(peer: NsdDiscovery.Peer): String = "${peer.host}:${peer.port}"

    fun myQrText(): String =
        QrPayload(QrPayload.CURRENT_VERSION, provider.schemeId, myHandle, deviceName).encode()

    fun isPinned(handle: String): Boolean = trustStore.isPinned(handle)

    /**
     * Every device this phone has paired with, discovered or not.
     *
     * Until now nothing enumerated the trust store into the UI — a paired device that wasn't
     * currently advertising simply didn't exist as far as the app was concerned. Sending by address
     * needs exactly that list, because pairing is what supplies the key.
     */
    fun pairedDevices(): List<com.relaypony.session.pairing.PinnedDevice> = trustStore.all()

    /** Export this device's identity + paired devices to [uri] as a passphrase-protected age file. */
    fun exportIdentity(uri: Uri, passphrase: String) {
        identityBusy.value = true
        setStatus("Exporting identity…")
        thread {
            val result = runCatching {
                appContext.contentResolver.openOutputStream(uri)?.use { out ->
                    IdentityBackup.export(
                        passphrase, provider.identityToString(identity), trustStore.all(), out,
                        inboxId = myInboxId, routes = peerRoutes.all(),
                    )
                } ?: error("couldn't open the destination file")
            }
            main.post {
                identityBusy.value = false
                showNotice(result.fold({ "Identity exported." }, { "Export failed: ${it.message ?: "unknown error"}" }))
            }
        }
    }

    /** Import an identity backup from [uri]: persist its keypair (takes effect next launch) and
     *  merge its paired devices into the trust store immediately. */
    fun importIdentity(uri: Uri, passphrase: String) {
        identityBusy.value = true
        setStatus("Importing identity…")
        thread {
            val result = runCatching {
                appContext.contentResolver.openInputStream(uri)?.use { IdentityBackup.import(passphrase, it) }
                    ?: error("couldn't open the backup file")
            }
            main.post {
                identityBusy.value = false
                result.fold(
                    { imported ->
                        identityStore.save(imported.identitySecret)
                        imported.devices.forEach { trustStore.pin(it.recipientHandle, it.name, it.pinnedAtEpochMs) }
                        imported.routes.forEach { (handle, route) -> peerRoutes.put(handle, route) }
                        imported.inboxId?.let { settings.edit().putString("inbox_id", it).apply() }
                        trustRevision.intValue++
                        refreshShareShortcuts()
                        showNotice("Imported ${imported.devices.size} device(s). Restart RelayPony to switch to the imported identity.")
                    },
                    { showNotice("Import failed: ${it.message ?: "unknown error"}") },
                )
            }
        }
    }

    /** Export ONLY the paired-devices address book to [uri] as a passphrase-protected age file.
     *  Decoupled from identity backup: no keypair travels, so it merges anywhere. */
    fun exportAddresses(uri: Uri, passphrase: String) {
        identityBusy.value = true
        setStatus("Exporting address book\u2026")
        thread {
            val result = runCatching {
                appContext.contentResolver.openOutputStream(uri)?.use { out ->
                    IdentityBackup.exportAddresses(passphrase, trustStore.all(), out)
                } ?: error("couldn't open the destination file")
            }
            main.post {
                identityBusy.value = false
                showNotice(result.fold({ "Address book exported." }, { "Export failed: ${it.message ?: "unknown error"}" }))
            }
        }
    }

    /** Import an address-book backup from [uri] and MERGE its devices into the trust store. Does not
     *  touch this device's identity. Also accepts a full identity backup (uses only its devices). */
    fun importAddresses(uri: Uri, passphrase: String) {
        identityBusy.value = true
        setStatus("Importing address book\u2026")
        thread {
            val result = runCatching {
                appContext.contentResolver.openInputStream(uri)?.use { IdentityBackup.importAddresses(passphrase, it) }
                    ?: error("couldn't open the backup file")
            }
            main.post {
                identityBusy.value = false
                result.fold(
                    { devices ->
                        devices.forEach { trustStore.pin(it.recipientHandle, it.name, it.pinnedAtEpochMs) }
                        trustRevision.intValue++
                        refreshShareShortcuts()
                        showNotice("Imported ${devices.size} address(es).")
                    },
                    { showNotice("Import failed: ${it.message ?: "unknown error"}") },
                )
            }
        }
    }

    fun needsStoragePermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                appContext,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) != PackageManager.PERMISSION_GRANTED

    /** Permissions Wi-Fi Direct discovery needs: NEARBY_WIFI_DEVICES on API 33+, else FINE_LOCATION. */
    fun wifiDirectPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(android.Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun setAutoSave(enabled: Boolean) {
        autoSave.value = enabled
        settings.edit().putBoolean(KEY_AUTOSAVE, enabled).apply()
        setStatus(if (enabled) str(R.string.st_autosave_on) else str(R.string.st_autosave_off))
    }

    fun finishOnboarding() {
        showOnboarding.value = false
        settings.edit().putBoolean(KEY_ONBOARDED, true).apply()
    }

    /** Show the intro again (from Settings). Does not clear the onboarded flag. */
    fun replayOnboarding() {
        showOnboarding.value = true
    }

    fun setLanguage(code: String) {
        languageCode.value = code
        settings.edit().putString(KEY_LANG, code).apply()
        // Keep the process default locale (DateUtils and other default-locale formatters) in sync with
        // the in-app language on a runtime switch; set before recomposition reads it.
        Locale.setDefault(
            if (code.isNotEmpty()) Locale.forLanguageTag(code)
            else {
                val cfg = Resources.getSystem().configuration
                @Suppress("DEPRECATION")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) cfg.locales[0] else cfg.locale
            }
        )
    }

    fun saveToDownloads(file: ReceivedFile) {
        thread(name = "relaypony-save") {
            val ok = DownloadsSaver.save(appContext, File(file.localPath), file.name, file.mime)
            main.post {
                if (ok) {
                    inboxStore.markSavedToDownloads(file.id)
                    refreshInbox()
                    setStatus(str(R.string.st_saved_dl, file.name))
                } else {
                    setStatus(str(R.string.st_save_failed, file.name))
                }
            }
        }
    }

    fun setPendingShare(files: List<OutgoingFile>) {
        pendingShare.clear()
        pendingShare.addAll(files)
        sweepFolderZips()
        setStatus(str(R.string.st_ready_send, files.size))
        // Shared in from another app, or picked on Home: straight to choosing who gets it.
        if (files.isNotEmpty()) sendToOpen.value = true
    }

    /** Stage typed or pasted text as a ClipDrop .txt file (plan section 5). */
    fun stageText(text: String) {
        if (text.isNotBlank()) setPendingShare(listOf(ClipText.toOutgoing(text)))
    }

    /** Drop the currently staged outgoing files. */
    fun clearPendingShare() {
        pendingShare.clear()
        sweepFolderZips()
        setStatus(str(R.string.st_cleared))
    }

    /** Stage files chosen via the in-app file picker (Storage Access Framework URIs). Routed
     *  through SharedFiles so the picker and the share-sheet path share one converter. */
    fun setPendingShareFromUris(uris: List<Uri>) {
        val files = SharedFiles.fromUris(appContext, uris)
        if (files.isEmpty()) {
            setStatus(str(R.string.st_read_failed))
            return
        }
        setPendingShare(files)
    }

    /** A peer held for verification before it is trusted (A2). The SAS is derived from both
     *  handles (sorted, so it is symmetric), which means the same six digits appear on the other
     *  device's verify sheet — the mutual check the iOS side has shown since its phase 7. */
    data class PendingVerify(val handle: String, val name: String, val sas: String)

    /** Non-null while the verify dialog should be showing. */
    val pendingVerify = mutableStateOf<PendingVerify?>(null)

    /** Decode a scanned QR and stage it for verification, without trusting it yet. */
    fun stageScan(qrText: String) {
        try {
            val payload = QrPayload.decode(qrText)
            pendingVerify.value = PendingVerify(
                payload.recipientHandle,
                payload.deviceName,
                Sas.code(myHandle, payload.recipientHandle),
            )
        } catch (t: Throwable) {
            setStatus(str(R.string.st_pairing_failed))
        }
    }

    /** Stage a device discovered over mDNS for verification. Both sides already know each
     *  other's handle from discovery, so no camera is needed and the codes match. */
    fun stageDiscovered(peer: NsdDiscovery.Peer) {
        pendingVerify.value = PendingVerify(
            peer.recipientHandle,
            peer.name,
            Sas.code(myHandle, peer.recipientHandle),
        )
    }

    /** Trust the staged peer after the user compared the code. Same pin as a scanned payload. */
    fun confirmVerify() {
        val pv = pendingVerify.value ?: return
        try {
            trustStore.pin(pv.handle, pv.name)
            trustRevision.intValue++
            refreshShareShortcuts()
            setStatus(str(R.string.st_paired_with, pv.name))
        } catch (t: Throwable) {
            setStatus(str(R.string.st_pairing_failed))
        }
        pendingVerify.value = null
    }

    fun dismissVerify() {
        pendingVerify.value = null
    }

    fun startReceiving() {
        wantsReceiving.value = true
        // Something other than pairing wants the listener now, so the end of pairing leaves it up.
        pairStartedListener = false
        if (serverSocket != null) return
        // A stable port, not whatever the OS hands out. An ephemeral port meant this device's
        // address was only valid for one run: unusable in a firewall rule, and impossible to tell
        // anyone when discovery isn't getting through. Fall back to ephemeral if it's taken —
        // being harder to find beats refusing to receive.
        val server = runCatching { ServerSocket(Beacon.DEFAULT_TRANSFER_PORT) }.getOrElse { ServerSocket(0) }
        serverSocket = server
        isReceiving.value = true
        val port = server.localPort
        listenPort.value = port
        thread(name = "relaypony-accept") {
            // The listener survives individual failed transfers (e.g. a sender resetting the
            // connection mid-stream). Only an intentional stop() — which closes the socket — ends
            // the loop. This is what keeps the Receive tab from going permanently stale after a reset.
            while (!server.isClosed) {
                val written = mutableListOf<Written>()
                try {
                    val result = SocketTransfer.acceptOne(
                        server, provider, identity,
                        // A one-shot PAIR frame (4.0): hand it to pairing on the main thread, as the
                        // relay path does. Nothing was received, so nothing to record below.
                        onPair = { sealed -> main.post { pairing.onLanPair(sealed) } },
                        deviceName = deviceName,
                        recipientHandle = myHandle,
                        onProgress = { recvd, total ->
                            main.post {
                                receiveInProgress.value = true
                                receiveProgress.value = if (total > 0) recvd.toFloat() / total else 1f
                            }
                        },
                        limits = receiveLimits(),
                        gate = { hello -> receiveGate(hello) },
                    ) { entry ->
                        val dir = File(appContext.filesDir, "inbox").apply { mkdirs() }
                        val outFile = uniqueFile(dir, FileNames.sanitize(entry.name))
                        // Record the sanitized on-disk name, never the sender's raw entry.name: this
                        // value becomes ReceivedFile.name and reaches DownloadsSaver (audit 2.3).
                        written.add(Written(outFile.name, entry.size, entry.mime, outFile.absolutePath))
                        outFile.outputStream()
                    } ?: continue
                    val label = senderLabel(result.senderHandle, result.senderName)
                    recordReceived(written, label)
                    main.post {
                        receiveInProgress.value = false
                        receiveProgress.value = 0f
                        setStatus(str(R.string.st_received, written.size, label), StatusKind.RECEIVED)
                    }
                } catch (e: RefusedException) {
                    // Refused at HELLO (section 9.2): nothing was written, and the sender is listed
                    // under Requests by the gate. Not a failed transfer.
                    continue
                } catch (t: Throwable) {
                    // Drop any half-written files from the aborted transfer so they never reach the inbox.
                    written.forEach { runCatching { File(it.path).delete() } }
                    main.post { receiveInProgress.value = false; receiveProgress.value = 0f }
                    if (server.isClosed) break
                    main.post { setStatus(str(R.string.st_receive_interrupted)) }
                }
            }
            // Loop ended because the socket closed; clear state so a later startReceiving() can re-arm.
            if (serverSocket === server) {
                runCatching { server.close() }
                serverSocket = null
            }
            main.post { isReceiving.value = false }
        }
        // pairCapable: this listener accepts PAIR frames, so peers may pair with us over the LAN.
        discovery.advertise("RelayPony-$port", port, deviceName, myHandle, pairCapable = true)
        acquireBeaconLock()
        beacon.listen(myHandle, ::addBeaconPeer)
        beacon.advertise(port, deviceName, myHandle, pairCapable = true)
        reachableAddresses.clear()
        reachableAddresses.addAll(LocalInterfaces.endpoints().map { "${it.ip}:$port" })
        setStatus(str(R.string.st_listening, port, deviceName))
    }

    /** Pause the LAN listener: stop advertising and stop accepting new connections. An in-flight
     *  transfer is allowed to finish; only new connections are refused. */
    fun stopReceiving() {
        wantsReceiving.value = false
        runCatching { discovery.stopAdvertising() }
        runCatching { beacon.stopAdvertising() }
        runCatching { serverSocket?.close() }
        serverSocket = null
        isReceiving.value = false
        listenPort.value = 0
        reachableAddresses.clear()
        setStatus(str(R.string.rec_paused_title))
    }

    fun startDiscovery() {
        // As above: browsing started here outlives the pair sheet.
        pairStartedBrowsing = false
        peers.clear()
        discovery.startDiscovery { peer -> addPeer(peer) }
        acquireBeaconLock()
        beacon.listen(myHandle, ::addBeaconPeer)
        probeForPeers()
        setStatus(str(R.string.st_discovering))
    }

    /**
     * Actively ask "anyone there?" over broadcast. mDNS browsing is passive and slow to notice a
     * device that started advertising after us; a probe gets an answer in well under a second, and
     * it is what the Refresh button should do.
     */
    fun probeForPeers() {
        thread(name = "relaypony-beacon-probe") {
            runCatching { beacon.probe(2000) { peer -> main.post { addBeaconPeer(peer) } } }
        }
    }

    /**
     * A beacon sighting is the same device mDNS would have reported, so it joins the same list.
     */
    private fun addBeaconPeer(peer: BeaconDiscovery.Peer) {
        addPeer(NsdDiscovery.Peer(peer.name, peer.host, peer.port, peer.recipientHandle, peer.maxWire, peer.pairCapable))
    }

    /**
     * Deduplicate on the handle, not on host:port. The same device can be reported by both
     * mechanisms, and can legitimately change address (a new DHCP lease, a different interface)
     * while remaining the same device — identity here is the key, never the address.
     */
    private fun addPeer(peer: NsdDiscovery.Peer) {
        val existing = peers.indexOfFirst { it.recipientHandle == peer.recipientHandle }
        if (existing >= 0) peers[existing] = peer else peers.add(peer)
    }

    /**
     * Send to an address typed by the user, with no discovery involved.
     *
     * The escape hatch for every network discovery can't cross. The peer's key comes from the
     * pairing — the one thing an address cannot supply — so this only works for a device already
     * pinned, and the security model is untouched: still encrypted to the pinned handle, we just
     * found the socket differently.
     */
    fun sendToAddress(host: String, port: Int, recipientHandle: String, name: String) {
        val cleanHost = host.trim()
        if (cleanHost.isEmpty() || port !in 1..65535) {
            setStatus(str(R.string.st_manual_bad_address))
            return
        }
        if (!Pairing.canSendOneTap(recipientHandle, trustStore)) {
            setStatus(str(R.string.st_manual_not_paired, name))
            return
        }
        if (pendingShare.isEmpty()) {
            setStatus(str(R.string.st_pick_files_first))
            return
        }
        sendToGroup(listOf(NsdDiscovery.Peer(name, cleanHost, port, recipientHandle)))
    }

    /**
     * Android drops inbound broadcast frames not addressed to this device unless a multicast lock
     * is held — the beacon would otherwise send fine and hear nothing back.
     */
    private fun acquireBeaconLock() {
        if (beaconLock != null) return
        beaconLock = runCatching {
            wifiManager.createMulticastLock("relaypony-beacon").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
    }

    private fun releaseBeaconLock() {
        runCatching { beaconLock?.takeIf { it.isHeld }?.release() }
        beaconLock = null
    }

    /**
     * Stop looking for other devices.
     *
     * The beacon socket is shared between the two jobs — it hears other devices' announcements
     * *and* answers their probes — so it is only torn down when this device isn't receiving
     * either. Closing it unconditionally here would make a phone sitting on its Receive tab go
     * quietly undiscoverable the moment the user left the Send tab.
     */
    fun stopDiscovery() {
        // Browsing only. discovery.stop() would also withdraw this device's own mDNS advertisement,
        // which made a phone on its Receive tab vanish from mDNS when the user left the Send tab.
        runCatching { discovery.stopDiscovery() }
        if (!isReceiving.value) {
            runCatching { beacon.close() }
            releaseBeaconLock()
        }
    }

    /** Send the current files (shared, or the 1 MB test blob) to every selected paired peer at
     *  once. Unpaired selections are skipped. Per-peer results stream into [sendStatus]. */
    fun sendToGroup(selected: List<NsdDiscovery.Peer>) {
        val sendable = selected.filter { Pairing.canSendOneTap(it.recipientHandle, trustStore) }
        if (sendable.isEmpty()) {
            setStatus(str(R.string.st_select_one))
            return
        }
        if (pendingShare.isEmpty()) return
        val files = pendingShare.toList()
        sendable.forEach { val k = peerKey(it); sendStatus[k] = str(R.string.st_sending); sendInProgress[k] = true; sendProgress[k] = 0f }
        setStatus(str(R.string.st_sending_to, sendable.size))
        thread(name = "relaypony-group-send") {
            FanOut.run(
                targets = sendable,
                onResult = { peer, outcome ->
                    main.post {
                        val k = peerKey(peer)
                        sendStatus[k] =
                            if (outcome.isSuccess) str(R.string.st_sent)
                            else str(R.string.st_failed, outcome.exceptionOrNull()?.message ?: "")
                        if (outcome.isSuccess) sendProgress[k] = 1f
                        sendInProgress[k] = false
                    }
                },
            ) { peer ->
                val key = peerKey(peer)
                val recipient = provider.recipientFromQr(peer.recipientHandle.toByteArray(Charsets.UTF_8))
                // Transient network failures (refused/reset/timeout) get a few backoff retries before
                // we report failure. Protocol/crypto errors are not IOExceptions, so they fail fast.
                var attempt = 0
                while (true) {
                    try {
                        SocketTransfer.sendTo(
                            peer.host, peer.port, provider, listOf(recipient), deviceName, myHandle, files,
                            peerMaxWire = peer.maxWire,
                            helloAuth = HelloAuth.signer(myScalar, myHandle, peer.recipientHandle),
                        ) { sent, total ->
                            main.post { sendProgress[key] = if (total > 0) sent.toFloat() / total else 1f }
                        }
                        break
                    } catch (e: java.io.IOException) {
                        attempt++
                        if (attempt >= SEND_MAX_ATTEMPTS) throw e
                        main.post {
                            sendProgress[key] = 0f
                            sendStatus[key] = str(R.string.st_send_retrying, attempt)
                        }
                        Thread.sleep(SEND_RETRY_BASE_MS * attempt)
                    }
                }
            }
            main.post { setStatus(str(R.string.st_group_finished, sendable.size)) }
        }
    }

    /** Delete a received file's local copy and its inbox record. A copy already saved to public
     *  Downloads is left in place (the user explicitly saved that one). */
    fun deleteReceived(file: ReceivedFile) {
        runCatching { File(file.localPath).delete() }
        inboxStore.remove(file.id)
        refreshInbox()
        setStatus(str(R.string.st_removed, file.name))
    }

    fun openFile(file: ReceivedFile) {
        openError.value = null
        try {
            val uri = FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                File(file.localPath),
            )
            // The sender's mime detection can fall back to the generic "application/octet-stream"
            // (both the Android and iOS clients do this when the platform can't classify the file),
            // which no video/image viewer declares a filter for. When we see that generic fallback,
            // re-derive a real mime from the file's extension instead — the extension is more
            // reliable here than whatever the sender managed to report.
            val effectiveMime = if (file.mime.isBlank() || file.mime == "application/octet-stream") {
                val ext = file.name.substringAfterLast('.', "").lowercase(Locale.US)
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: file.mime
            } else {
                file.mime
            }
            // Request the broad major type (image/*, video/*) rather than the exact subtype.
            // Android's intent-filter matching is wildcard-symmetric in both directions, so this
            // only widens which viewer apps match — it can't exclude one that matched before —
            // and it catches viewers that only declared the wildcard type themselves.
            val isMedia = effectiveMime.startsWith("image/") || effectiveMime.startsWith("video/")
            val viewType = when {
                effectiveMime.startsWith("image/") -> "image/*"
                effectiveMime.startsWith("video/") -> "video/*"
                else -> effectiveMime
            }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, viewType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            // For images/video, check for a handler up front so a TV with no installed viewer gets
            // a clear, translated message instead of a silent no-op or a raw exception string. This
            // check needs the matching <queries> entries in the manifest to see real apps on API 30+;
            // other mime types keep the old start-and-catch path since we can't declare <queries> for
            // every arbitrary type a received file might be.
            val matches = appContext.packageManager
                .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            if (isMedia && matches.isEmpty()) {
                openError.value = str(R.string.st_open_no_viewer, file.name)
                return
            }
            // Explicit chooser rather than an implicit launch: with exactly one match, plain
            // startActivity() silently jumps straight into that app with no confirmation, which is
            // indistinguishable from "nothing happened" if that app can't actually read a content://
            // URI and fails silently inside its own process. The chooser always shows what RelayPony
            // thinks can open the file, so a bad match is visible instead of a dead end.
            val chooser = Intent.createChooser(intent, file.name).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(chooser)
        } catch (t: Throwable) {
            openError.value = str(R.string.st_open_failed, file.name, t.message ?: "")
        }
    }

    private fun recordReceived(written: List<Written>, senderName: String) {
        val now = System.currentTimeMillis()
        val records = written.mapIndexed { i, w ->
            ReceivedFile(
                id = "$now-$i-${w.name}",
                name = w.name,
                size = w.size,
                mime = w.mime,
                fromDevice = senderName,
                receivedAtEpochMs = now,
                localPath = w.path,
            )
        }
        records.forEach { inboxStore.add(it) }
        if (autoSave.value) {
            records.forEach { rec ->
                if (DownloadsSaver.save(appContext, File(rec.localPath), rec.name, rec.mime)) {
                    inboxStore.markSavedToDownloads(rec.id)
                }
            }
        }
        main.post { refreshInbox() }
    }

    // --- Wi-Fi Direct transfer (Phase 7b) ---

    /** Arm this device to send or receive over a Wi-Fi Direct link. The transfer begins as soon as
     *  a group forms (via the Wi-Fi Direct Discover/Connect controls). One-shot per arming. */
    fun armWifiDirect(asSender: Boolean) {
        wifiAsSender = asSender
        wifiArmed = true
        wifiTransferStatus.value =
            if (asSender) UiText(R.string.st_wifi_armed_send)
            else UiText(R.string.st_wifi_armed_recv)
        val addr = wifiDirect.groupOwnerAddress.value
        if (addr != null) onWifiConnected(wifiDirect.isGroupOwner.value, addr)
    }

    private fun onWifiConnected(isGroupOwner: Boolean, goAddress: String?) {
        if (!wifiArmed) return
        wifiArmed = false
        val asSender = wifiAsSender
        thread(name = "relaypony-wifi") {
            try {
                val mine = Ident(provider.schemeId.toInt(), myHandle, deviceName, asSender)
                val (theirs, peerIp) = exchangeIdent(isGroupOwner, goAddress, mine)
                val iSend = WifiIdent.resolveISend(mine, theirs)
                if (iSend) {
                    if (!Pairing.canSendOneTap(theirs.handle, trustStore)) {
                        postWifi(UiText(R.string.st_wifi_not_paired, theirs.deviceName))
                        return@thread
                    }
                    if (pendingShare.isEmpty()) return@thread
                    val files = pendingShare.toList()
                    sendOverWifi(peerIp, theirs.handle, files, theirs.deviceName)
                } else {
                    receiveOverWifi(theirs.deviceName)
                }
            } catch (t: Throwable) {
                postWifi(UiText(R.string.st_wifi_failed, t.message ?: ""))
            }
        }
    }

    /** Exchange [Ident]s over the formed link. The group owner listens; the client connects to it.
     *  Returns the peer's identity and the peer's IP (the sender later opens the transfer to it). */
    private fun exchangeIdent(isGroupOwner: Boolean, goAddress: String?, mine: Ident): Pair<Ident, String> {
        postWifi(UiText(R.string.st_wifi_exchanging))
        if (isGroupOwner) {
            ServerSocket(PORT_IDENT).use { server ->
                server.soTimeout = IDENT_TIMEOUT_MS
                server.accept().use { sock ->
                    val peerIp = sock.inetAddress?.hostAddress ?: "unknown"
                    WifiIdent.writeTo(sock.getOutputStream(), mine)
                    val theirs = WifiIdent.readFrom(sock.getInputStream())
                    return theirs to peerIp
                }
            }
        }
        val addr = goAddress ?: throw IllegalStateException("no group owner address")
        connectWithRetry(addr, PORT_IDENT, IDENT_CONNECT_ATTEMPTS).use { sock ->
            WifiIdent.writeTo(sock.getOutputStream(), mine)
            val theirs = WifiIdent.readFrom(sock.getInputStream())
            return theirs to addr
        }
    }

    private fun sendOverWifi(peerIp: String, theirHandle: String, files: List<OutgoingFile>, theirName: String) {
        val recipient = provider.recipientFromQr(theirHandle.toByteArray(Charsets.UTF_8))
        postWifi(UiText(R.string.st_wifi_sending, files.size, theirName))
        var attempt = 0
        while (true) {
            try {
                SocketTransfer.sendTo(
                    peerIp, PORT_TRANSFER, provider, listOf(recipient), deviceName, myHandle, files,
                )
                postWifi(UiText(R.string.st_wifi_sent, files.size, theirName))
                return
            } catch (e: java.net.ConnectException) {
                if (++attempt >= TRANSFER_CONNECT_ATTEMPTS) throw e
                Thread.sleep(TRANSFER_RETRY_MS)
            }
        }
    }

    private fun receiveOverWifi(theirName: String) {
        postWifi(UiText(R.string.st_wifi_receiving, theirName))
        ServerSocket(PORT_TRANSFER).use { server ->
            server.soTimeout = TRANSFER_TIMEOUT_MS
            val written = mutableListOf<Written>()
            val result = try {
                SocketTransfer.receiveOnceFrom(server, provider, identity, limits = receiveLimits()) { entry ->
                    val dir = File(appContext.filesDir, "inbox").apply { mkdirs() }
                    val outFile = uniqueFile(dir, FileNames.sanitize(entry.name))
                    // Sanitized on-disk name only, as in the LAN accept loop (audit 2.3).
                    written.add(Written(outFile.name, entry.size, entry.mime, outFile.absolutePath))
                    outFile.outputStream()
                }
            } catch (t: Throwable) {
                // Same cleanup as the LAN accept loop: a capped or aborted transfer leaves nothing behind.
                written.forEach { runCatching { File(it.path).delete() } }
                throw t
            }
            recordReceived(written, result.senderName)
            postWifi(UiText(R.string.st_wifi_received, written.size, result.senderName))
        }
    }

    /** Receive ceilings for every inbound path (audit 2.5), with free space measured on the
     *  volume that holds the inbox. */
    private fun receiveLimits(): TransferLimits {
        val inbox = File(appContext.filesDir, "inbox").apply { mkdirs() }
        return TransferLimits(freeBytes = { inbox.usableSpace })
    }

    private fun connectWithRetry(host: String, port: Int, attempts: Int): Socket {
        var last: Exception? = null
        repeat(attempts) {
            try {
                return Socket(host, port)
            } catch (e: Exception) {
                last = e
                Thread.sleep(TRANSFER_RETRY_MS)
            }
        }
        throw last ?: IllegalStateException("could not connect to $host:$port")
    }

    private fun postWifi(message: UiText) {
        main.post { wifiTransferStatus.value = message }
    }

    private fun testBlob(): OutgoingFile {
        val data = ByteArray(1 shl 20).also { SecureRandom().nextBytes(it) } // 1 MiB
        return OutgoingFile("testblob.bin", "application/octet-stream", data.size.toLong()) {
            ByteArrayInputStream(data)
        }
    }

    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 1
        while (candidate.exists()) {
            candidate = File(dir, "$base ($n)$ext")
            n++
        }
        return candidate
    }

    // ---- 4.0: always ready while open (plan section 9.1) ----

    private var foreground = false
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null

    /**
     * The app came to the front (true) or went to the background (false). While it is in front
     * this device listens on the LAN, polls its relay inbox and browses for nearby devices, so
     * there is no Receive tab to remember. In-flight transfers finish either way.
     */
    fun setForeground(front: Boolean) {
        if (front == foreground) return
        foreground = front
        if (front) {
            startReceiving()
            startWANReceive()
            startDiscovery()
            watchNetworks(true)
        } else {
            watchNetworks(false)
            stopReceiving()
            stopWANReceive()
            stopDiscovery()
        }
    }

    /** Keep the reachable addresses (and so the Home status) current as Wi-Fi comes and goes. */
    private fun watchNetworks(on: Boolean) {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return
        networkCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        networkCallback = null
        if (!on) return
        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) { main.post { refreshAddresses() } }
            override fun onLost(network: android.net.Network) { main.post { refreshAddresses() } }
        }
        val request = android.net.NetworkRequest.Builder()
            .removeCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        if (runCatching { cm.registerNetworkCallback(request, cb) }.isSuccess) networkCallback = cb
    }

    private fun refreshAddresses() {
        val port = listenPort.value
        if (!isReceiving.value || port == 0) return
        reachableAddresses.clear()
        reachableAddresses.addAll(LocalInterfaces.endpoints().map { "${it.ip}:$port" })
        probeForPeers()
    }

    fun stop() {
        watchNetworks(false)
        runCatching { wan.stop() }
        runCatching { discovery.stop() }
        runCatching { beacon.close() }
        releaseBeaconLock()
        runCatching { serverSocket?.close() }
        serverSocket = null
        listenPort.value = 0
        reachableAddresses.clear()
    }

    // --- WAN transfer (paired, not on the same LAN) ---

    /** Begin accepting incoming WAN transfers while the Receive tab is open. */
    fun startWANReceive() { wan.startReceiving(); wanReceiveActive.value = true }

    /** Stop accepting new WAN transfers. In-flight receives finish. */
    fun stopWANReceive() { wan.stopReceiving(); wanReceiveActive.value = false }

    // ---- 4.0 send flow: the Send-to sheet and the transfer screen (plan sections 4.3, 4.4, 8.1) ----

    /** Whether the Send-to sheet is up. */
    val sendToOpen = mutableStateOf(false)

    /** Devices to tick when the Send-to sheet opens (Direct Share, Send more). */
    val sendToPreselect = mutableStateOf<Set<String>>(emptySet())

    /** The send the transfer screen shows, null when there is none. */
    val batch = mutableStateOf<OutgoingBatch?>(null)

    /** Whether the transfer screen is in front (it can be put away while a send runs). */
    val transferVisible = mutableStateOf(false)

    private var batchFiles: List<OutgoingFile> = emptyList()
    private val lanSockets = ConcurrentHashMap<String, Socket>()
    private val lanCancelled: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Every paired device for the Send-to sheet: nearby ones first, then by name. */
    fun sendTargets(): List<SendTarget> {
        val nearby = peers.map { it.recipientHandle }.toSet()
        return trustStore.all()
            .map { SendTarget(it.recipientHandle, it.name, it.recipientHandle in nearby) }
            .sortedWith(compareByDescending<SendTarget> { it.nearby }.thenBy { it.name.lowercase(Locale.ROOT) })
    }

    /**
     * Send the staged content to [handles], each by the route that reaches it (plan section 8.1):
     * the LAN when it is discovered here, the internet otherwise. Opens the transfer screen.
     */
    fun sendToDevices(handles: List<String>) {
        if (pendingShare.isEmpty() || handles.isEmpty()) return
        if (batch.value?.active == true) {
            showNotice(str(R.string.xfer_busy))
            return
        }
        val files = pendingShare.toList()
        val nearby = handles.associateWith { h -> peers.firstOrNull { it.recipientHandle == h } }
        val legs = handles.distinct().mapNotNull { h ->
            val device = trustStore.get(h) ?: return@mapNotNull null
            SendLeg(h, device.name, if (nearby[h] != null) SendRoute.NEARBY else SendRoute.INTERNET, LegState.CONNECTING)
        }
        if (legs.isEmpty()) return
        batchFiles = files
        batch.value = OutgoingBatch(System.currentTimeMillis(), files.map { it.name }, files.sumOf { it.size }, legs)
        transferVisible.value = true
        sendToOpen.value = false
        sendToPreselect.value = emptySet()
        legs.forEach { leg -> startLeg(leg.handle, nearby[leg.handle], files) }
    }

    private fun startLeg(handle: String, nearby: NsdDiscovery.Peer?, files: List<OutgoingFile>) {
        if (nearby != null) {
            sendLan(nearby, files)
        } else {
            updateLeg(handle) { it.copy(route = SendRoute.INTERNET, state = LegState.CONNECTING, progress = null, error = null) }
            wan.sendWAN(files, handle, deviceName, myHandle)
        }
    }

    /** Try a failed device again, by whichever route reaches it now. */
    fun retryLeg(handle: String) {
        val leg = batch.value?.legs?.firstOrNull { it.handle == handle } ?: return
        if (leg.active || batchFiles.isEmpty()) return
        val nearby = peers.firstOrNull { it.recipientHandle == handle }
        updateLeg(handle) {
            it.copy(route = if (nearby != null) SendRoute.NEARBY else SendRoute.INTERNET, state = LegState.CONNECTING, progress = null, error = null, refused = false)
        }
        startLeg(handle, nearby, batchFiles)
    }

    /** One LAN send with a few retries on network errors. Falls back to the internet if it fails. */
    private fun sendLan(peer: NsdDiscovery.Peer, files: List<OutgoingFile>) {
        val h = peer.recipientHandle
        lanCancelled.remove(h)
        thread(name = "relaypony-lan-send") {
            val result = runCatching {
                val recipient = provider.recipientFromQr(h.toByteArray(Charsets.UTF_8))
                var attempt = 0
                var lastPost = 0L
                while (true) {
                    if (h in lanCancelled) throw java.io.IOException("stopped")
                    try {
                        SocketTransfer.sendTo(
                            peer.host, peer.port, provider, listOf(recipient), deviceName, myHandle, files,
                            peerMaxWire = peer.maxWire,
                            helloAuth = HelloAuth.signer(myScalar, myHandle, h),
                            onProgress = { sent, total ->
                                val now = System.currentTimeMillis()
                                if (now - lastPost >= 150 || sent >= total) {
                                    lastPost = now
                                    val p = if (total > 0) sent.toFloat() / total else 1f
                                    main.post { updateLeg(h) { if (it.active) it.copy(state = LegState.SENDING, progress = p) else it } }
                                }
                            },
                            onSocket = { lanSockets[h] = it },
                        )
                        break
                    } catch (e: RefusedException) {
                        throw e
                    } catch (e: java.io.IOException) {
                        if (h in lanCancelled) throw e
                        attempt++
                        if (attempt >= SEND_MAX_ATTEMPTS) throw e
                        Thread.sleep(SEND_RETRY_BASE_MS * attempt)
                    }
                }
            }
            lanSockets.remove(h)
            main.post {
                when {
                    h in lanCancelled -> updateLeg(h) { it.copy(state = LegState.CANCELLED) }
                    result.isSuccess -> updateLeg(h) { it.copy(state = LegState.SENT, progress = 1f) }
                    // It's there but doesn't have this device paired: no point trying the internet.
                    result.exceptionOrNull() is RefusedException -> updateLeg(h) {
                        it.copy(state = LegState.FAILED, refused = true, error = str(R.string.xfer_refused, it.name))
                    }
                    // Discovery can be stale (the device left the network): try the internet.
                    else -> startLeg(h, null, files)
                }
            }
        }
    }

    /** Map a WAN send's progress onto its leg. Ignored for legs going over the LAN. */
    private fun onWanLegStatus(peer: String, st: WanStatus) {
        updateLeg(peer) { leg ->
            if (leg.route == SendRoute.NEARBY) return@updateLeg leg
            when (st.kind) {
                WanStatusKind.CONNECTING -> leg.copy(route = SendRoute.INTERNET, state = LegState.CONNECTING)
                WanStatusKind.SENDING -> leg.copy(route = SendRoute.INTERNET_DIRECT, state = LegState.SENDING)
                WanStatusKind.SENDING_RELAY -> leg.copy(route = SendRoute.INTERNET_RELAY, state = LegState.SENDING)
                WanStatusKind.SENT -> leg.copy(state = LegState.SENT, progress = 1f)
                WanStatusKind.CANCELLED -> leg.copy(state = LegState.CANCELLED)
                WanStatusKind.SEND_FAILED, WanStatusKind.PREPARE_FAILED ->
                    leg.copy(state = LegState.FAILED, error = st.arg)
                else -> leg
            }
        }
    }

    private fun updateLeg(handle: String, change: (SendLeg) -> SendLeg) {
        val b = batch.value ?: return
        if (b.legs.none { it.handle == handle }) return
        batch.value = b.copy(legs = b.legs.map { if (it.handle == handle) change(it) else it })
    }

    /** The Stop button: end every send still running. */
    fun stopSending() {
        val b = batch.value ?: return
        b.legs.filter { it.active }.forEach { leg ->
            if (leg.route == SendRoute.NEARBY) {
                lanCancelled.add(leg.handle)
                runCatching { lanSockets[leg.handle]?.close() }
                updateLeg(leg.handle) { it.copy(state = LegState.CANCELLED) }
            } else {
                wan.cancelSend(leg.handle)
                updateLeg(leg.handle) { if (it.active) it.copy(state = LegState.CANCELLED) else it }
            }
        }
    }

    /** Done on the transfer screen: the staged content is spent. */
    fun closeTransfer() {
        if (batch.value?.active == true) return
        batch.value = null
        batchFiles = emptyList()
        transferVisible.value = false
        pendingShare.clear()
        sweepFolderZips()
    }

    /** "Send more": back to Home, with the same devices ticked for whatever is picked next. */
    fun sendMore() {
        val handles = batch.value?.legs?.map { it.handle }?.toSet() ?: emptySet()
        closeTransfer()
        sendToPreselect.value = handles
    }

    /** Send the staged files to a paired device over the internet. */
    fun sendWAN(device: com.relaypony.session.pairing.PinnedDevice) {
        if (pendingShare.isEmpty()) { setStatus(str(R.string.st_pick_files_first)); return }
        wan.sendWAN(pendingShare.toList(), device.recipientHandle, deviceName, myHandle)
    }

    /** File a completed WAN receive into the inbox, mirroring [recordReceived]. */
    private fun recordWanReceived(batch: WanTransfer.ReceivedBatch) {
        val now = System.currentTimeMillis()
        val records = batch.files.mapIndexed { i, f ->
            ReceivedFile(
                id = "$now-$i-${f.name}",
                name = f.name,
                size = f.size,
                mime = f.mime,
                fromDevice = trustStore.get(batch.peerHandle)?.name ?: batch.senderName,
                receivedAtEpochMs = now,
                localPath = f.path,
            )
        }
        records.forEach { inboxStore.add(it) }
        if (autoSave.value) {
            records.forEach { rec ->
                if (DownloadsSaver.save(appContext, File(rec.localPath), rec.name, rec.mime)) {
                    inboxStore.markSavedToDownloads(rec.id)
                }
            }
        }
        refreshInbox()
        setStatus(str(R.string.st_received, records.size, records.firstOrNull()?.fromDevice ?: batch.senderName), StatusKind.RECEIVED)
    }

    private data class Written(val name: String, val size: Long, val mime: String, val path: String)

    // ---- 4.0 content: folders (PROTOCOL_v3 section 12) ----

    /** A folder being zipped before the Send-to sheet opens. */
    data class FolderPrep(val name: String, val done: Long, val total: Long)

    val folderPrep = mutableStateOf<FolderPrep?>(null)

    /** The received file being extracted, by id. */
    val extractingId = mutableStateOf<String?>(null)

    @Volatile private var folderCancelled = false
    private val folderZips = ConcurrentHashMap<OutgoingFile, File>()

    private fun folderZipDir(): File = File(appContext.cacheDir, "folders")

    /** Zip a folder from the folder picker in the background, then stage it like any file. */
    fun stageFolder(treeUri: Uri) {
        if (folderPrep.value != null) return
        folderCancelled = false
        folderPrep.value = FolderPrep("", 0, 0)
        thread(name = "relaypony-folder") {
            var zip: File? = null
            try {
                val folder = PickedFolder.walk(appContext, treeUri, WireProtocol.MAX_MANIFEST_ENTRIES)
                if (folder.fileCount == 0) throw FolderProblem(R.string.folder_empty)
                val total = folder.totalBytes
                if (total > TransferLimits.MAX_TRANSFER_BYTES) throw FolderProblem(R.string.folder_too_big)
                if (folder.items.any { it.size > TransferLimits.MAX_FILE_BYTES }) throw FolderProblem(R.string.folder_too_big)
                val dir = folderZipDir().apply { mkdirs() }
                if (total + TransferLimits.FREE_SPACE_MARGIN_BYTES > dir.usableSpace) throw FolderProblem(R.string.folder_no_space)
                main.post { folderPrep.value = FolderPrep(folder.name, 0, total) }
                val name = com.relaypony.session.FolderZip.archiveName(folder.name)
                val zipFile = File(dir, "${System.nanoTime()}-$name")
                zip = zipFile
                var done = 0L
                var lastPost = 0L
                zipFile.outputStream().buffered(256 * 1024).use { out ->
                    com.relaypony.session.FolderZip.write(out, folder.name, folder.items, cancelled = { folderCancelled }) { n ->
                        done += n
                        if (done - lastPost >= (1L shl 20)) {
                            lastPost = done
                            val d = done
                            main.post { folderPrep.value = folderPrep.value?.copy(done = d) }
                        }
                    }
                }
                val outgoing = OutgoingFile(name, "application/zip", zipFile.length()) { zipFile.inputStream() }
                folderZips[outgoing] = zipFile
                main.post {
                    folderPrep.value = null
                    if (folderCancelled) sweepFolderZips() else setPendingShare(listOf(outgoing))
                }
            } catch (e: com.relaypony.session.FolderZip.CancelledException) {
                zip?.delete()
                main.post { folderPrep.value = null }
            } catch (e: FolderProblem) {
                zip?.delete()
                main.post { folderPrep.value = null; showNotice(str(e.res, WireProtocol.MAX_MANIFEST_ENTRIES)) }
            } catch (e: PickedFolder.TooManyItemsException) {
                main.post { folderPrep.value = null; showNotice(str(R.string.folder_too_many, WireProtocol.MAX_MANIFEST_ENTRIES)) }
            } catch (t: Throwable) {
                zip?.delete()
                main.post { folderPrep.value = null; showNotice(str(R.string.folder_read_failed)) }
            }
        }
    }

    fun cancelFolder() { folderCancelled = true }

    private class FolderProblem(val res: Int) : Exception()

    /** Delete folder zips nothing staged or sending refers to any more. */
    private fun sweepFolderZips() {
        val inUse = (pendingShare.toList() + batchFiles).toSet()
        folderZips.keys.filter { it !in inUse }.forEach { key -> folderZips.remove(key)?.delete() }
    }

    /** Extract a received .zip into Download/RelayPony (section 12.4). */
    fun extractFolder(file: ReceivedFile) {
        if (extractingId.value != null) return
        extractingId.value = file.id
        thread(name = "relaypony-extract") {
            val result = runCatching { FolderExtractor.extract(appContext, File(file.localPath), file.name) }
            main.post {
                extractingId.value = null
                result.fold(
                    { r ->
                        showNotice(
                            if (r.skipped > 0) str(R.string.ext_done_skipped, r.files, r.folder, r.skipped)
                            else str(R.string.ext_done, r.files, r.folder)
                        )
                    },
                    { e -> showNotice(extractProblem(file.name, e)) },
                )
            }
        }
    }

    private fun extractProblem(name: String, e: Throwable): String {
        val why = when (e) {
            is FolderExtractor.EmptyArchiveException -> return str(R.string.ext_empty, name)
            is com.relaypony.session.UnsafeArchiveException -> when (e.reason) {
                com.relaypony.session.ZipRefusal.UNSAFE_PATH -> R.string.ext_why_unsafe
                com.relaypony.session.ZipRefusal.ENCRYPTED -> R.string.ext_why_encrypted
                com.relaypony.session.ZipRefusal.UNSUPPORTED_METHOD -> R.string.ext_why_method
                com.relaypony.session.ZipRefusal.TOO_LARGE,
                com.relaypony.session.ZipRefusal.TOO_MANY_ENTRIES,
                com.relaypony.session.ZipRefusal.TOO_DEEP -> R.string.ext_why_large
                com.relaypony.session.ZipRefusal.NO_SPACE -> R.string.ext_why_space
                else -> R.string.ext_why_damaged
            }
            else -> R.string.ext_why_write
        }
        return str(R.string.ext_failed, name, str(why))
    }

    companion object {
        /** Dynamic-shortcut id prefix; the suffix is the peer's recipient handle (A4). */
        const val SHORTCUT_PREFIX = "relaypony_peer_"
        /** Must match the category in res/xml/shortcuts.xml (A4). */
        const val SHARE_CATEGORY = "com.relaypony.android.directshare.SEND"
        private const val KEY_AUTOSAVE = "autosave"
        private const val KEY_ONBOARDED = "onboarded"
        private const val KEY_LANG = "lang"
        private const val KEY_THEME = "theme"
        private const val KEY_ACCEPT_UNPAIRED = "accept_unpaired"
        private const val TAG_MEMORY_MS = 20 * 60 * 1000L
        private const val MAX_REQUESTS = 10
        private const val SEND_MAX_ATTEMPTS = 3
        private const val SEND_RETRY_BASE_MS = 800L
        private const val PORT_IDENT = 8987
        private const val PORT_TRANSFER = 8988
        private const val IDENT_TIMEOUT_MS = 25000
        private const val TRANSFER_TIMEOUT_MS = 60000
        private const val IDENT_CONNECT_ATTEMPTS = 20
        private const val TRANSFER_CONNECT_ATTEMPTS = 20
        private const val TRANSFER_RETRY_MS = 500L
    }
}
