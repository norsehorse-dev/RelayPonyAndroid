package com.relaypony.android.transfer

import android.content.Context
import com.relaypony.session.inbox.InboxStore
import com.relaypony.session.inbox.ReceivedFile
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Persistent [InboxStore] backed by app-private SharedPreferences, storing the received-file list
 * as JSON so it survives restarts. The file bytes themselves live under filesDir/inbox; this only
 * holds the metadata records.
 */
class PrefsInboxStore(context: Context) : InboxStore {
    private val prefs = context.applicationContext
        .getSharedPreferences("relaypony_inbox", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(ReceivedFile.serializer())
    private val items = LinkedHashMap<String, ReceivedFile>()

    init {
        prefs.getString(KEY, null)?.let { raw ->
            runCatching { json.decodeFromString(serializer, raw) }
                .getOrNull()
                ?.forEach { items[it.id] = it }
        }
    }

    override fun add(file: ReceivedFile) {
        items[file.id] = file
        persist()
    }

    override fun all(): List<ReceivedFile> =
        items.values.sortedByDescending { it.receivedAtEpochMs }

    override fun get(id: String): ReceivedFile? = items[id]

    override fun markSavedToDownloads(id: String) {
        items[id]?.let { items[id] = it.copy(savedToDownloads = true); persist() }
    }

    override fun remove(id: String) {
        items.remove(id)
        persist()
    }

    override fun clear() {
        items.clear()
        persist()
    }

    private fun persist() {
        prefs.edit().putString(KEY, json.encodeToString(serializer, items.values.toList())).apply()
    }

    companion object {
        private const val KEY = "inbox_json"
    }
}
