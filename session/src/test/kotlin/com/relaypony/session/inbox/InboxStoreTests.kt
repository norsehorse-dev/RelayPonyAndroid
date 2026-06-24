package com.relaypony.session.inbox

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InboxStoreTests {

    private fun file(id: String, ts: Long) =
        ReceivedFile(id, "$id.bin", 10, "application/octet-stream", "Phone", ts, "/data/$id.bin")

    @Test
    fun allReturnsNewestFirst() {
        val store = InMemoryInboxStore()
        store.add(file("a", 1000))
        store.add(file("c", 3000))
        store.add(file("b", 2000))
        assertEquals(listOf("c", "b", "a"), store.all().map { it.id })
    }

    @Test
    fun addWithSameIdReplaces() {
        val store = InMemoryInboxStore()
        store.add(file("a", 1000))
        store.add(file("a", 5000))
        assertEquals(1, store.all().size)
        assertEquals(5000L, store.get("a")?.receivedAtEpochMs)
    }

    @Test
    fun markSavedFlipsFlagIdempotently() {
        val store = InMemoryInboxStore()
        store.add(file("a", 1000))
        assertFalse(store.get("a")!!.savedToDownloads)
        store.markSavedToDownloads("a")
        store.markSavedToDownloads("a")
        assertTrue(store.get("a")!!.savedToDownloads)
        assertEquals(1, store.all().size)
    }

    @Test
    fun removeAndClear() {
        val store = InMemoryInboxStore()
        store.add(file("a", 1000))
        store.add(file("b", 2000))
        store.remove("a")
        assertNull(store.get("a"))
        assertEquals(listOf("b"), store.all().map { it.id })
        store.clear()
        assertTrue(store.all().isEmpty())
    }
}
