package com.relaypony.android

import android.content.Context
import android.content.Intent
import com.relaypony.android.transfer.LegState
import com.relaypony.android.transfer.OutgoingBatch
import com.relaypony.android.transfer.PairingController
import com.relaypony.android.transfer.PrefsInboxStore
import com.relaypony.android.transfer.PrefsTrustStore
import com.relaypony.android.transfer.SendLeg
import com.relaypony.android.transfer.SendRoute
import com.relaypony.android.transfer.TransferController
import com.relaypony.session.OutgoingFile
import com.relaypony.session.inbox.ReceivedFile
import com.relaypony.transport.NsdDiscovery
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Store-screenshot demo mode, debug builds only. tools/play-screenshots.sh launches the app with
 * `demo_lang` (en, de, es, fr, hi, ja, pt-BR), `demo_screen` (home, sendto, transfer, received,
 * pair, word) and optionally `demo_theme` (dark, light). [handle] runs before anything reads
 * preferences: it marks onboarding done, picks the language and theme, and seeds two paired
 * devices and a few received items. [show] then puts the requested screen in front once the
 * controller is up. The release source set has a stub with the same API that does nothing.
 *
 * Demo data: no real people, no real addresses. Device names are product names only.
 */
object Demo {
    /** True for the rest of the process once a demo launch was seen. */
    var active: Boolean = false
        private set

    /** Overrides Build.MODEL as this device's name while demo mode is on. */
    val deviceName: String? get() = if (active) "Pixel 10" else null

    /** Tab index RelayPonyApp starts on (0 Send, 1 Received). */
    var startTab: Int = 0
        private set

    private const val NEARBY_HANDLE = "age1demo0nearby0pixel09pro0000000000000000000000000000000000"
    private const val FAR_HANDLE = "age1demo0internet0iphone0170000000000000000000000000000000000"
    private const val NEARBY_NAME = "Pixel 9 Pro"
    private const val FAR_NAME = "iPhone 17"
    private const val WORD_CODE = "23-harbor-tulip-ember"
    private const val RELAY = "https://relaypony.app"

    private class Strings(val folderZip: String, val link: String)

    private val strings = mapOf(
        "en" to Strings("Vacation 2026.zip", "https://relaypony.app/faq"),
        "de" to Strings("Urlaub 2026.zip", "https://relaypony.app/faq"),
        "es" to Strings("Vacaciones 2026.zip", "https://relaypony.app/faq"),
        "fr" to Strings("Vacances 2026.zip", "https://relaypony.app/faq"),
        "hi" to Strings("छुट्टियाँ 2026.zip", "https://relaypony.app/faq"),
        "ja" to Strings("旅行 2026.zip", "https://relaypony.app/faq"),
        "pt-BR" to Strings("Férias 2026.zip", "https://relaypony.app/faq"),
    )

    private val sendFiles = listOf(
        Triple("IMG_4021.jpg", "image/jpeg", 3_145_728L),
        Triple("IMG_4022.jpg", "image/jpeg", 2_936_012L),
        Triple("VID_0147.mp4", "video/mp4", 43_921_408L),
    )

    /**
     * Seed state from the launch intent. Returns the screen to show, or null when this is not a
     * demo launch. Call before preferences are read (first thing in Activity.onCreate).
     */
    fun handle(context: Context, intent: Intent?): String? {
        val lang = intent?.getStringExtra("demo_lang") ?: return null
        val screen = intent.getStringExtra("demo_screen") ?: "home"
        val theme = intent.getStringExtra("demo_theme") ?: "dark"
        active = true
        startTab = if (screen == "received") 1 else 0

        val app = context.applicationContext
        app.getSharedPreferences("relaypony_settings", Context.MODE_PRIVATE).edit()
            .putBoolean("onboarded", true)
            .putString("lang", lang)
            .putString("theme", if (theme == "light") TransferController.ThemeMode.LIGHT.name else TransferController.ThemeMode.DARK.name)
            .putBoolean("autosave", false)
            .putBoolean("accept_unpaired", false)
            .apply()

        val trust = PrefsTrustStore(app)
        trust.all().forEach { trust.remove(it.recipientHandle) }
        val now = System.currentTimeMillis()
        trust.pin(NEARBY_HANDLE, NEARBY_NAME, now - 9L * 24 * 3600 * 1000)
        trust.pin(FAR_HANDLE, FAR_NAME, now - 40L * 24 * 3600 * 1000)

        seedInbox(app, lang, now)
        return screen
    }

    private fun seedInbox(app: Context, lang: String, now: Long) {
        val s = strings[lang] ?: strings.getValue("en")
        val dir = File(app.filesDir, "inbox/demo").apply { mkdirs() }
        fun write(name: String, bytes: ByteArray): File = File(dir, name).apply { writeBytes(bytes) }
        val photo = write("IMG_3310.jpg", ByteArray(1024))
        val zip = write("folder.zip", ByteArray(1024))
        val linkBytes = s.link.toByteArray(Charsets.UTF_8)
        val link = write("clip-20261015-091204.txt", linkBytes)

        val inbox = PrefsInboxStore(app)
        inbox.clear()
        inbox.add(ReceivedFile("demo-1", "IMG_3310.jpg", 2_516_582L, "image/jpeg", NEARBY_NAME, now - 2L * 60 * 1000, photo.absolutePath))
        inbox.add(ReceivedFile("demo-2", s.folderZip, 134_217_728L, "application/zip", FAR_NAME, now - 25L * 60 * 1000, zip.absolutePath))
        inbox.add(ReceivedFile("demo-3", "clip-20261015-091204.txt", linkBytes.size.toLong(), "text/uri-list", NEARBY_NAME, now - 70L * 60 * 1000, link.absolutePath))
    }

    /** Put the requested screen in front. Call after the controller exists and the app is in front. */
    fun show(controller: TransferController, screen: String) {
        if (!active) return
        when (screen) {
            "sendto" -> {
                nearby(controller)
                controller.setPendingShare(outgoing())
            }
            "transfer" -> {
                nearby(controller)
                controller.batch.value = OutgoingBatch(
                    id = System.currentTimeMillis(),
                    fileNames = sendFiles.map { it.first },
                    totalBytes = sendFiles.sumOf { it.third },
                    legs = listOf(
                        SendLeg(NEARBY_HANDLE, NEARBY_NAME, SendRoute.NEARBY, LegState.SENDING, progress = 0.62f),
                        SendLeg(FAR_HANDLE, FAR_NAME, SendRoute.INTERNET_DIRECT, LegState.SENDING, progress = 0.31f),
                    ),
                )
                controller.transferVisible.value = true
            }
            "pair" -> controller.pair.open(PairingController.Tab.QR)
            "word" -> {
                controller.pair.open(PairingController.Tab.WORD)
                controller.pair.word.value = PairingController.WordCode.Showing(WORD_CODE, RELAY)
            }
            else -> Unit
        }
    }

    /** The nearby paired device, as discovery would have listed it. */
    private fun nearby(controller: TransferController) {
        if (controller.peers.none { it.recipientHandle == NEARBY_HANDLE }) {
            controller.peers.add(NsdDiscovery.Peer(NEARBY_NAME, "192.0.2.24", 7070, NEARBY_HANDLE, maxWire = 2, pairCapable = true))
        }
    }

    private fun outgoing(): List<OutgoingFile> = sendFiles.map { (name, mime, size) ->
        OutgoingFile(name, mime, size) { ByteArrayInputStream(ByteArray(0)) }
    }
}
