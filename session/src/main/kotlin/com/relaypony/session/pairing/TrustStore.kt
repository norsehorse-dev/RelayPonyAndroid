package com.relaypony.session.pairing

/** A device this user has paired with, identified by its recipient handle (public key). */
data class PinnedDevice(
    val recipientHandle: String,
    val name: String,
    val pinnedAtEpochMs: Long,
)

/**
 * Store of paired devices. The security-critical invariant: trust is keyed on the recipient
 * HANDLE (the public key), never on the display name. A network party can spoof a name freely;
 * it cannot produce a handle whose private key it doesn't hold. The Android-backed implementation
 * (encrypted, persistent) arrives in Phase 4b; this interface and the in-memory implementation are
 * what the pairing logic and its tests run against.
 */
interface TrustStore {
    fun pin(handle: String, name: String, nowMs: Long = System.currentTimeMillis())
    fun isPinned(handle: String): Boolean
    fun get(handle: String): PinnedDevice?
    fun all(): List<PinnedDevice>
    fun remove(handle: String)
}

/** In-memory TrustStore: the reference implementation and the one used in tests. */
class InMemoryTrustStore : TrustStore {
    private val byHandle = LinkedHashMap<String, PinnedDevice>()

    override fun pin(handle: String, name: String, nowMs: Long) {
        require(handle.isNotEmpty()) { "cannot pin an empty handle" }
        byHandle[handle] = PinnedDevice(handle, name, nowMs)
    }

    override fun isPinned(handle: String): Boolean = byHandle.containsKey(handle)

    override fun get(handle: String): PinnedDevice? = byHandle[handle]

    override fun all(): List<PinnedDevice> = byHandle.values.toList()

    override fun remove(handle: String) { byHandle.remove(handle) }
}
