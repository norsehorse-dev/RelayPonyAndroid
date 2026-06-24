package com.relaypony.session.inbox

/**
 * Store of received files. [all] returns newest-first. Entries are keyed by [ReceivedFile.id];
 * adding an entry with an existing id replaces it (so status flips like "saved to Downloads" are
 * idempotent). The Android-backed persistent implementation lives in the app module; this
 * interface and the in-memory implementation are what the inbox logic and its tests run against.
 */
interface InboxStore {
    fun add(file: ReceivedFile)
    fun all(): List<ReceivedFile>
    fun get(id: String): ReceivedFile?
    fun markSavedToDownloads(id: String)
    fun remove(id: String)
    fun clear()
}

class InMemoryInboxStore : InboxStore {
    private val byId = LinkedHashMap<String, ReceivedFile>()

    override fun add(file: ReceivedFile) {
        byId[file.id] = file
    }

    override fun all(): List<ReceivedFile> =
        byId.values.sortedByDescending { it.receivedAtEpochMs }

    override fun get(id: String): ReceivedFile? = byId[id]

    override fun markSavedToDownloads(id: String) {
        byId[id]?.let { byId[id] = it.copy(savedToDownloads = true) }
    }

    override fun remove(id: String) {
        byId.remove(id)
    }

    override fun clear() {
        byId.clear()
    }
}
