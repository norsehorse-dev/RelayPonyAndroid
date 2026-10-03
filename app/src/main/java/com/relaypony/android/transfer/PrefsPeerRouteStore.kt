package com.relaypony.android.transfer

import android.content.Context
import com.relaypony.session.pairing.PeerRoute
import com.relaypony.session.pairing.PeerRouteStore

/**
 * Where each paired device can be reached on the relay (its private inbox and its relay), learned
 * during 4.0 pairing or from an inbox announcement. Separate from [PrefsTrustStore] so the pinned
 * model is unchanged. Format per entry: "<inboxId>\u0001<relay>".
 */
class PrefsPeerRouteStore(context: Context) : PeerRouteStore {
    private val prefs = context.applicationContext.getSharedPreferences("relaypony_routes", Context.MODE_PRIVATE)

    override fun get(handle: String): PeerRoute? {
        val raw = prefs.getString(handle, null) ?: return null
        val i = raw.indexOf('\u0001')
        return if (i < 0) PeerRoute(raw, "") else PeerRoute(raw.substring(0, i), raw.substring(i + 1))
    }

    override fun put(handle: String, route: PeerRoute) {
        prefs.edit().putString(handle, route.inboxId + "\u0001" + route.relay).apply()
    }

    override fun remove(handle: String) {
        prefs.edit().remove(handle).apply()
    }

    fun all(): Map<String, PeerRoute> = prefs.all.keys.mapNotNull { k -> get(k)?.let { k to it } }.toMap()
}
