package com.relaypony.android.transfer

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.relaypony.android.R
import com.relaypony.crypto.AgeProvider
import com.relaypony.session.FanOut
import com.relaypony.session.FileNames
import com.relaypony.session.OutgoingFile
import com.relaypony.session.Ident
import com.relaypony.session.SocketTransfer
import com.relaypony.session.WifiIdent
import com.relaypony.session.inbox.ReceivedFile
import com.relaypony.session.pairing.Pairing
import java.util.Locale
import com.relaypony.session.pairing.QrPayload
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

    /** True while a file is actively being received (drives the receive progress card). */
    val receiveInProgress = mutableStateOf(false)

    /** Current receive progress in 0f..1f. */
    val receiveProgress = mutableStateOf(0f)

    /** Classifies the last status update so the UI never parses display text. */
    val lastStatusKind = mutableStateOf(StatusKind.OTHER)

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
        wifiDirect.onConnected = { isGroupOwner, goAddress -> onWifiConnected(isGroupOwner, goAddress) }
    }

    private fun refreshInbox() {
        inbox.clear()
        inbox.addAll(inboxStore.all())
    }

    fun peerKey(peer: NsdDiscovery.Peer): String = "${peer.host}:${peer.port}"

    fun myQrText(): String =
        QrPayload(QrPayload.CURRENT_VERSION, provider.schemeId, myHandle, deviceName).encode()

    fun isPinned(handle: String): Boolean = trustStore.isPinned(handle)

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

    fun pinFromScan(qrText: String) {
        try {
            val payload = QrPayload.decode(qrText)
            Pairing.pinScanned(payload, trustStore)
            trustRevision.intValue++
            setStatus(str(R.string.st_paired_with, payload.deviceName))
        } catch (t: Throwable) {
            setStatus(str(R.string.st_pairing_failed))
        }
    }

    fun startReceiving() {
        wantsReceiving.value = true
        if (serverSocket != null) return
        val server = ServerSocket(0)
        serverSocket = server
        isReceiving.value = true
        val port = server.localPort
        thread(name = "relaypony-accept") {
            // The listener survives individual failed transfers (e.g. a sender resetting the
            // connection mid-stream). Only an intentional stop() — which closes the socket — ends
            // the loop. This is what keeps the Receive tab from going permanently stale after a reset.
            while (!server.isClosed) {
                val written = mutableListOf<Written>()
                try {
                    val result = SocketTransfer.receiveOnceFrom(
                        server, provider, identity,
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
        setStatus(str(R.string.st_listening, port, deviceName))
    }

    /** Pause the LAN listener: stop advertising and stop accepting new connections. An in-flight
     *  transfer is allowed to finish; only new connections are refused. */
    fun stopReceiving() {
        wantsReceiving.value = false
        runCatching { discovery.stopAdvertising() }
        runCatching { serverSocket?.close() }
        serverSocket = null
        isReceiving.value = false
        setStatus(str(R.string.rec_paused_title))
    }

    fun startDiscovery() {
        peers.clear()
        discovery.startDiscovery { peer ->
            if (peers.none { it.host == peer.host && it.port == peer.port }) {
                peers.add(peer)
            }
        }
        setStatus(str(R.string.st_discovering))
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
        try {
            val uri = FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                File(file.localPath),
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, file.mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)
        } catch (t: Throwable) {
            setStatus(str(R.string.st_open_failed, file.name, t.message ?: ""))
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
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private data class Written(val name: String, val size: Long, val mime: String, val path: String)

    companion object {
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
