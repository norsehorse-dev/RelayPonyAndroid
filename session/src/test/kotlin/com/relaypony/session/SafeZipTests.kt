package com.relaypony.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64

/**
 * The folder extractor against the shared corpus (docs/vectors/zip_corpus.json, written by
 * gen_zip_corpus.py). The Swift SafeZipTests assert the same outcomes.
 */
class SafeZipTests {

    private val corpus: JsonObject by lazy {
        val file = listOf("../docs/vectors/zip_corpus.json", "docs/vectors/zip_corpus.json")
            .map(::File).first { it.exists() }
        Json.parseToJsonElement(file.readText()).jsonObject
    }

    private fun limitsFor(case: JsonObject): TransferLimits {
        val d = corpus["defaults"]!!.jsonObject
        val l = case["limits"]!!.jsonObject
        fun num(key: String) = (l[key] ?: d[key])!!
        val free = num("freeBytes")
        return TransferLimits(
            maxFileBytes = num("maxFileBytes").jsonPrimitive.long,
            maxTransferBytes = num("maxTransferBytes").jsonPrimitive.long,
            maxEntries = num("maxEntries").jsonPrimitive.int,
            freeSpaceMarginBytes = num("freeSpaceMarginBytes").jsonPrimitive.long,
            freeBytes = if (free is JsonNull) null else free.jsonPrimitive.long.let { v -> { v } },
        )
    }

    @TestFactory
    fun corpus(): List<DynamicTest> = corpus["cases"]!!.jsonArray.map { it.jsonObject }.map { case ->
        val id = case["id"]!!.jsonPrimitive.content
        DynamicTest.dynamicTest(id) {
            val bytes = Base64.getDecoder().decode(case["zip"]!!.jsonPrimitive.content)
            val expect = case["expect"]!!.jsonObject
            val written = LinkedHashMap<String, ByteArrayOutputStream>()
            var phase = "plan"
            val outcome = runCatching {
                val source = ByteArrayZipSource(bytes)
                val plan = SafeZip.plan(source, case["archiveName"]!!.jsonPrimitive.content, limitsFor(case))
                phase = "extract"
                SafeZip.extract(source, plan) { f -> ByteArrayOutputStream().also { written[f.path] = it } }
                plan
            }
            val error = expect["error"]
            if (error != null) {
                val e = outcome.exceptionOrNull()
                assertTrue(e is UnsafeArchiveException, "$id: expected ${error.jsonPrimitive.content}, got $e")
                assertEquals(error.jsonPrimitive.content, (e as UnsafeArchiveException).reason.name, id)
                assertEquals(expect["phase"]!!.jsonPrimitive.content, phase, "$id phase")
                if (id == "size_lie_bomb") {
                    assertTrue(written.values.all { it.size() <= 1000 }, "wrote past the declared size")
                }
            } else {
                val plan = outcome.getOrThrow()
                assertEquals(expect["root"]!!.jsonPrimitive.content, plan.root, "$id root")
                val files = expect["files"]!!.jsonArray.map { it.jsonObject }
                assertEquals(files.map { it["path"]!!.jsonPrimitive.content }, plan.files.map { it.path }, "$id paths")
                files.forEach { f ->
                    val path = f["path"]!!.jsonPrimitive.content
                    assertEquals(f["content"]!!.jsonPrimitive.content, written[path]!!.toString(Charsets.UTF_8.name()), "$id $path")
                }
                assertEquals(expect["dirs"]!!.jsonArray.map { it.jsonPrimitive.content }, plan.dirs, "$id dirs")
                assertEquals(expect["skipped"]!!.jsonPrimitive.int, plan.skipped, "$id skipped")
            }
        }
    }

    @Test
    fun folderZipRoundTrips() {
        val items = listOf(
            FolderZip.Item("a.txt", 5) { ByteArrayInputStream("alpha".toByteArray()) },
            FolderZip.Item("trip/b.jpg", 3) { ByteArrayInputStream(byteArrayOf(1, 2, 3)) },
            FolderZip.Item("Café/c.txt", 1) { ByteArrayInputStream("c".toByteArray()) },
            FolderZip.Item("empty", 0, isDir = true) { ByteArrayInputStream(ByteArray(0)) },
            FolderZip.Item("../sneaky.txt", 1) { ByteArrayInputStream("s".toByteArray()) },
        )
        val zip = ByteArrayOutputStream().also { FolderZip.write(it, "Holiday", items) }.toByteArray()
        val source = ByteArrayZipSource(zip)
        val plan = SafeZip.plan(source, FolderZip.archiveName("Holiday"))
        assertEquals("Holiday", plan.root)
        assertEquals(listOf("a.txt", "trip/b.jpg", "Café/c.txt", "sneaky.txt"), plan.files.map { it.path })
        assertEquals(listOf("empty"), plan.dirs)
        val out = LinkedHashMap<String, ByteArrayOutputStream>()
        SafeZip.extract(source, plan) { f -> ByteArrayOutputStream().also { out[f.path] = it } }
        assertEquals("alpha", out["a.txt"]!!.toString("UTF-8"))
        assertEquals(listOf<Byte>(1, 2, 3), out["trip/b.jpg"]!!.toByteArray().toList())
    }

    @Test
    fun folderZipStopsWhenCancelled() {
        val items = listOf(FolderZip.Item("a.txt", 1) { ByteArrayInputStream("a".toByteArray()) })
        assertThrows(FolderZip.CancelledException::class.java) {
            FolderZip.write(ByteArrayOutputStream(), "X", items, cancelled = { true })
        }
    }

    @Test
    fun largeFolderZipUsesZip64AndExtracts() {
        // 70,000 empty files push the entry count past the 16-bit field, forcing zip64 records.
        val items = (0 until 70_000).map { i -> FolderZip.Item("f$i", 0) { ByteArrayInputStream(ByteArray(0)) } }
        val zip = ByteArrayOutputStream().also { FolderZip.write(it, "Many", items) }.toByteArray()
        val plan = SafeZip.plan(ByteArrayZipSource(zip), "Many.zip", TransferLimits(maxEntries = 80_000))
        assertEquals(70_000, plan.files.size)
        assertEquals("f69999", plan.files.last().path)
    }
}
