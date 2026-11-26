package com.relaypony.android.transfer

import android.content.Context
import com.relaypony.session.pairing.PinnedDevice
import com.relaypony.session.pairing.TrustStore

/**
 * Persistent [TrustStore] backed by app-private SharedPreferences. Stores only public recipient
 * handles and display names (not secret), one entry per device keyed by handle. App sandboxing
 * provides the confidentiality these public values need; the security property lives in the
 * pairing logic, which keys trust on the handle.
 *
 * A device can also carry a nickname the user chose here (4.1). It is kept beside the pin, never
 * leaves this device, and wins over the name the device gave for every label; [pin] only ever
 * replaces the given name, so pairing again or an inbox announcement can't undo a rename.
 */
class PrefsTrustStore(context: Context) : TrustStore {
    private val prefs = context.applicationContext
        .getSharedPreferences("relaypony_trust", Context.MODE_PRIVATE)

    override fun pin(handle: String, name: String, nowMs: Long) {
        require(handle.isNotEmpty()) { "cannot pin an empty handle" }
        prefs.edit().putString(PREFIX + handle, "$nowMs\u0001$name").apply()
    }

    override fun isPinned(handle: String): Boolean = prefs.contains(PREFIX + handle)

    override fun get(handle: String): PinnedDevice? {
        val raw = prefs.getString(PREFIX + handle, null) ?: return null
        val (name, ts) = split(raw)
        return PinnedDevice(handle, nickname(handle) ?: name, ts)
    }

    override fun all(): List<PinnedDevice> = prefs.all.entries
        .filter { it.key.startsWith(PREFIX) }
        .mapNotNull { entry ->
            (entry.value as? String)?.let {
                val (name, ts) = split(it)
                val handle = entry.key.removePrefix(PREFIX)
                PinnedDevice(handle, nickname(handle) ?: name, ts)
            }
        }

    override fun remove(handle: String) {
        prefs.edit().remove(PREFIX + handle).remove(NICK_PREFIX + handle).apply()
    }

    /** The nickname the user gave [handle] on this device, or null when it has none. */
    fun nickname(handle: String): String? =
        prefs.getString(NICK_PREFIX + handle, null)?.takeIf { it.isNotEmpty() }

    /** The name the device gave for itself when it paired, whatever it is called here. */
    fun givenName(handle: String): String? =
        prefs.getString(PREFIX + handle, null)?.let { split(it).first }

    /** Set or, with a blank [nickname], clear the local name for a paired device. */
    fun rename(handle: String, nickname: String) {
        if (!isPinned(handle)) return
        val edit = prefs.edit()
        if (nickname.isBlank()) edit.remove(NICK_PREFIX + handle) else edit.putString(NICK_PREFIX + handle, nickname)
        edit.apply()
    }

    private fun split(raw: String): Pair<String, Long> {
        val i = raw.indexOf('\u0001')
        if (i < 0) return raw to 0L
        return raw.substring(i + 1) to (raw.substring(0, i).toLongOrNull() ?: 0L)
    }

    companion object {
        private const val PREFIX = "pin_"
        private const val NICK_PREFIX = "nick_"
    }
}
