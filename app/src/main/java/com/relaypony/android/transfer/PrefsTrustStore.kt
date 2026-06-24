package com.relaypony.android.transfer

import android.content.Context
import com.relaypony.session.pairing.PinnedDevice
import com.relaypony.session.pairing.TrustStore

/**
 * Persistent [TrustStore] backed by app-private SharedPreferences. Stores only public recipient
 * handles and display names (not secret), one entry per device keyed by handle. App sandboxing
 * provides the confidentiality these public values need; the security property lives in the
 * pairing logic, which keys trust on the handle.
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
        return PinnedDevice(handle, name, ts)
    }

    override fun all(): List<PinnedDevice> = prefs.all.entries
        .filter { it.key.startsWith(PREFIX) }
        .mapNotNull { entry ->
            (entry.value as? String)?.let {
                val (name, ts) = split(it)
                PinnedDevice(entry.key.removePrefix(PREFIX), name, ts)
            }
        }

    override fun remove(handle: String) {
        prefs.edit().remove(PREFIX + handle).apply()
    }

    private fun split(raw: String): Pair<String, Long> {
        val i = raw.indexOf('\u0001')
        if (i < 0) return raw to 0L
        return raw.substring(i + 1) to (raw.substring(0, i).toLongOrNull() ?: 0L)
    }

    companion object {
        private const val PREFIX = "pin_"
    }
}
