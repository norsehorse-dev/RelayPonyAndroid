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
import com.relaypony.session.FileNames
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
import com.relaypony.transport.BeaconDiscovery
import com.relaypony.transport.LocalInterfaces
import com.relaypony.transport.NsdDiscovery
import java.io.ByteArrayInputStream
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
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

    /** This device's recipient handle (age1 string), advertised over mDNS and shown in its QR. */
    val myHandle: String = String(provider.recipientToQr(myRecipient), Charsets.UTF_8)
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
        wifiDirect.onConnected = { isGroupOwner, goAddress -> onWifiConnected(isGroupOwner, goAddress) }
    }

    /** A4: a handle a Direct Share target asked us to pre-select on the Send screen, or null.
     *  Consumed by SendScreen once the matching peer is discovered; best-effort by nature. */
    val preselectHandle = mutableStateOf<String?>(null)

    /** Called from MainActivity when the app was opened via a Direct Share target. Remembers the
     *  device to pre-check and makes sure discovery is running so it can actually be found. */
    fun preselectForSend(handle: String) {
        preselectHandle.value = handle
        startDiscovery()
    }

    /** A4: publish the paired devices as Direct Share targets, newest pins first, capped to the
     *  launcher's per-activity limit. Called on launch and whenever a new device is pinned; there
     *  is no unpair path on Android today, so this set only grows until reinstall. All wrapped in
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
                    IdentityBackup.export(passphrase, provider.identityToString(identity), trustStore.all(), out)
                } ?: error("couldn't open the destination file")
            }
            main.post {
                identityBusy.value = false
                setStatus(result.fold({ "Identity exported." }, { "Export failed: ${it.message ?: "unknown error"}" }))
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
                        trustRevision.intValue++
                        refreshShareShortcuts()
                        setStatus("Imported ${imported.devices.size} device(s). Restart RelayPony to switch to the imported identity.")
                    },
                    { setStatus("Import failed: ${it.message ?: "unknown error"}") },
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
        setStatus(str(R.string.st_ready_send, files.size))
    }

    /** Drop the currently staged outgoing files. */
    fun clearPendingShare() {
        pendingShare.clear()
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
                    val result = SocketTransfer.receiveOnceFrom(
                        server, provider, identity,
                        deviceName = deviceName,
                        recipientHandle = myHandle,
                        onProgress = { recvd, total ->
                            main.post {
                                receiveInProgress.value = true
                                receiveProgress.value = if (total > 0) recvd.toFloat() / total else 1f
                            }
                        },
                    ) { entry ->
                        val dir = File(appContext.filesDir, "inbox").apply { mkdirs() }
                        val outFile = uniqueFile(dir, FileNames.sanitize(entry.name))
                        written.add(Written(entry.name, entry.size, entry.mime, outFile.absolutePath))
                        outFile.outputStream()
                    }
                    recordReceived(written, result.senderName)
                    main.post {
                        receiveInProgress.value = false
                        receiveProgress.value = 0f
                        setStatus(str(R.string.st_received, written.size, result.senderName), StatusKind.RECEIVED)
                    }
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
        discovery.advertise("RelayPony-$port", port, deviceName, myHandle)
        acquireBeaconLock()
        beacon.listen(myHandle, ::addBeaconPeer)
        beacon.advertise(port, deviceName, myHandle)
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
        addPeer(NsdDiscovery.Peer(peer.name, peer.host, peer.port, peer.recipientHandle, peer.maxWire))
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
        runCatching { discovery.stop() }
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
            val result = SocketTransfer.receiveOnceFrom(server, provider, identity) { entry ->
                val dir = File(appContext.filesDir, "inbox").apply { mkdirs() }
                val outFile = uniqueFile(dir, FileNames.sanitize(entry.name))
                written.add(Written(entry.name, entry.size, entry.mime, outFile.absolutePath))
                outFile.outputStream()
            }
            recordReceived(written, result.senderName)
            postWifi(UiText(R.string.st_wifi_received, written.size, result.senderName))
        }
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

    fun stop() {
        runCatching { discovery.stop() }
        runCatching { beacon.close() }
        releaseBeaconLock()
        runCatching { serverSocket?.close() }
        serverSocket = null
        listenPort.value = 0
        reachableAddresses.clear()
    }

    private data class Written(val name: String, val size: Long, val mime: String, val path: String)

    companion object {
        /** Dynamic-shortcut id prefix; the suffix is the peer's recipient handle (A4). */
        const val SHORTCUT_PREFIX = "relaypony_peer_"
        /** Must match the category in res/xml/shortcuts.xml (A4). */
        const val SHARE_CATEGORY = "com.relaypony.android.directshare.SEND"
        private const val KEY_AUTOSAVE = "autosave"
        private const val KEY_ONBOARDED = "onboarded"
        private const val KEY_LANG = "lang"
        private const val KEY_THEME = "theme"
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
