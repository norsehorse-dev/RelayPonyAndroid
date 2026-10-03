package com.relaypony.session

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.text.Normalizer
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** Random access to an archive's bytes. */
interface ZipSource : Closeable {
    val size: Long

    /** Reads up to [len] bytes at [pos]; returns the count read, or -1 at the end. */
    fun readAt(pos: Long, buf: ByteArray, off: Int, len: Int): Int
}

class FileZipSource(file: File) : ZipSource {
    private val raf = RandomAccessFile(file, "r")
    override val size: Long = raf.length()

    override fun readAt(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
        raf.seek(pos)
        return raf.read(buf, off, len)
    }

    override fun close() = raf.close()
}

class ByteArrayZipSource(private val bytes: ByteArray) : ZipSource {
    override val size: Long = bytes.size.toLong()

    override fun readAt(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (pos >= bytes.size) return -1
        val n = minOf(len.toLong(), bytes.size - pos).toInt()
        System.arraycopy(bytes, pos.toInt(), buf, off, n)
        return n
    }

    override fun close() {}
}

/** Why an archive was refused (PROTOCOL_v3 section 12.5). */
enum class ZipRefusal {
    NOT_A_ZIP, MULTI_DISK, CORRUPT, ENCRYPTED, UNSUPPORTED_METHOD, UNSAFE_PATH, TOO_DEEP,
    TOO_MANY_ENTRIES, TOO_LARGE, NO_SPACE, CONFLICT, OVERLAP,
}

class UnsafeArchiveException(val reason: ZipRefusal, detail: String) : Exception("$reason: $detail")

/**
 * The folder extractor (PROTOCOL_v3 section 12). Every archive is treated as hostile: [plan]
 * reads the central directory and checks every entry before anything is written, and [extract]
 * writes each file through a caller-supplied sink, stopping at the declared size and checking the
 * CRC. Nothing here touches the filesystem; the caller decides where the paths land, so symlinks,
 * absolute paths and parent references can never reach it.
 */
object SafeZip {
    const val MAX_DEPTH = 32
    const val MAX_COMPONENT_BYTES = 200
    private const val MAX_CENTRAL_DIRECTORY_BYTES = 64L shl 20

    private const val SIG_LOCAL = 0x04034b50L
    private const val SIG_CENTRAL = 0x02014b50L
    private const val SIG_EOCD = 0x06054b50L
    private const val SIG_EOCD64 = 0x06064b50L
    private const val SIG_LOCATOR = 0x07064b50L

    /** One file to write. [path] is relative to the root folder, '/'-separated. */
    class FileEntry internal constructor(
        val path: String,
        val size: Long,
        internal val method: Int,
        internal val crc: Long,
        internal val compressedSize: Long,
        internal val dataStart: Long,
    )

    /**
     * What an archive will produce: a root folder name, the files in central-directory order,
     * the explicit (possibly empty) directories, and how many entries were skipped (symlinks,
     * Finder metadata, entries with no name left).
     */
    class Plan internal constructor(
        val root: String,
        val files: List<FileEntry>,
        val dirs: List<String>,
        val skipped: Int,
    ) {
        val totalBytes: Long get() = files.sumOf { it.size }
    }

    private class Raw(
        val comps: List<String>,
        val isDir: Boolean,
        val size: Long,
        val method: Int,
        val crc: Long,
        val compressedSize: Long,
        val dataStart: Long,
    )

    fun plan(source: ZipSource, archiveName: String, limits: TransferLimits = TransferLimits.DEFAULT): Plan {
        val size = source.size
        if (size < 22) refuse(ZipRefusal.NOT_A_ZIP, "too short")

        // End of central directory: the last record whose comment runs exactly to the end.
        val tailLen = minOf(size, 22L + 0xFFFF).toInt()
        val tail = readFully(source, size - tailLen, tailLen)
        var eocdAt = -1
        var i = tailLen - 22
        while (i >= 0) {
            if (le32(tail, i) == SIG_EOCD && i + 22 + le16(tail, i + 20) == tailLen) { eocdAt = i; break }
            i--
        }
        if (eocdAt < 0) refuse(ZipRefusal.NOT_A_ZIP, "no end of central directory")
        val eocdPos = size - tailLen + eocdAt
        val disk = le16(tail, eocdAt + 4)
        val cdDisk = le16(tail, eocdAt + 6)
        var onDisk = le16(tail, eocdAt + 8).toLong()
        var total = le16(tail, eocdAt + 10).toLong()
        var cdSize = le32(tail, eocdAt + 12)
        var cdOffset = le32(tail, eocdAt + 16)
        var cdLimit = eocdPos
        if (disk != 0 || cdDisk != 0 || onDisk != total) refuse(ZipRefusal.MULTI_DISK, "spanned archive")

        if (total == 0xFFFFL || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) {
            if (eocdPos < 20) refuse(ZipRefusal.CORRUPT, "zip64 locator missing")
            val loc = readFully(source, eocdPos - 20, 20)
            if (le32(loc, 0) != SIG_LOCATOR) refuse(ZipRefusal.CORRUPT, "zip64 locator missing")
            if (le32(loc, 4) != 0L || le32(loc, 16) > 1L) refuse(ZipRefusal.MULTI_DISK, "spanned zip64 archive")
            val eocd64Pos = le64(loc, 8)
            if (eocd64Pos < 0 || eocd64Pos + 56 > eocdPos - 20) refuse(ZipRefusal.CORRUPT, "zip64 record out of bounds")
            val rec = readFully(source, eocd64Pos, 56)
            if (le32(rec, 0) != SIG_EOCD64) refuse(ZipRefusal.CORRUPT, "zip64 record missing")
            if (le32(rec, 16) != 0L || le32(rec, 20) != 0L) refuse(ZipRefusal.MULTI_DISK, "spanned zip64 archive")
            onDisk = le64(rec, 24)
            total = le64(rec, 32)
            cdSize = le64(rec, 40)
            cdOffset = le64(rec, 48)
            if (onDisk != total) refuse(ZipRefusal.MULTI_DISK, "spanned zip64 archive")
            cdLimit = eocd64Pos
        }
        if (total < 0 || total > limits.maxEntries) {
            refuse(ZipRefusal.TOO_MANY_ENTRIES, "$total entries (limit ${limits.maxEntries})")
        }
        if (cdOffset < 0 || cdSize < 0 || cdSize > MAX_CENTRAL_DIRECTORY_BYTES || cdOffset + cdSize > cdLimit) {
            refuse(ZipRefusal.CORRUPT, "central directory out of bounds")
        }
        val cd = readFully(source, cdOffset, cdSize.toInt())

        val raws = ArrayList<Raw>()
        val spans = ArrayList<LongArray>()
        var skipped = 0
        var declared = 0L
        var p = 0
        for (n in 0 until total) {
            if (p + 46 > cd.size || le32(cd, p) != SIG_CENTRAL) refuse(ZipRefusal.CORRUPT, "bad central header")
            val madeBy = le16(cd, p + 4)
            val flags = le16(cd, p + 8)
            val method = le16(cd, p + 10)
            val crc = le32(cd, p + 16)
            var csize = le32(cd, p + 20)
            var usize = le32(cd, p + 24)
            val nameLen = le16(cd, p + 28)
            val extraLen = le16(cd, p + 30)
            val commentLen = le16(cd, p + 32)
            var diskStart = le16(cd, p + 34).toLong()
            val extAttr = le32(cd, p + 38)
            var localOffset = le32(cd, p + 42)
            val nameAt = p + 46
            val extraAt = nameAt + nameLen
            val next = extraAt + extraLen + commentLen
            if (next > cd.size) refuse(ZipRefusal.CORRUPT, "central header overruns")

            if (usize == 0xFFFFFFFFL || csize == 0xFFFFFFFFL || localOffset == 0xFFFFFFFFL || diskStart == 0xFFFFL) {
                var q = extraAt
                var found = false
                while (q + 4 <= extraAt + extraLen) {
                    val id = le16(cd, q)
                    val len = le16(cd, q + 2)
                    if (q + 4 + len > extraAt + extraLen) break
                    if (id == 1) {
                        var r = q + 4
                        val end = r + len
                        fun take8(): Long {
                            if (r + 8 > end) refuse(ZipRefusal.CORRUPT, "short zip64 field")
                            return le64(cd, r).also { r += 8 }
                        }
                        if (usize == 0xFFFFFFFFL) usize = take8()
                        if (csize == 0xFFFFFFFFL) csize = take8()
                        if (localOffset == 0xFFFFFFFFL) localOffset = take8()
                        if (diskStart == 0xFFFFL) {
                            if (r + 4 > end) refuse(ZipRefusal.CORRUPT, "short zip64 field")
                            diskStart = le32(cd, r)
                        }
                        found = true
                        break
                    }
                    q += 4 + len
                }
                if (!found) refuse(ZipRefusal.CORRUPT, "zip64 field missing")
            }
            if (diskStart != 0L) refuse(ZipRefusal.MULTI_DISK, "entry on another disk")
            if (usize < 0 || csize < 0 || localOffset < 0) refuse(ZipRefusal.CORRUPT, "size out of range")

            if (flags and 1 != 0) refuse(ZipRefusal.ENCRYPTED, "encrypted entry")
            if (method != 0 && method != 8) refuse(ZipRefusal.UNSUPPORTED_METHOD, "method $method")

            val name = decodeName(cd, nameAt, nameLen).replace('\\', '/')
            val isDir = name.endsWith("/")
            val comps = splitPath(name)
            val isLink = (madeBy shr 8) == 3 && ((extAttr ushr 16) and 0xF000L) == 0xA000L

            if (!isDir && usize > limits.maxFileBytes) {
                refuse(ZipRefusal.TOO_LARGE, "entry is $usize bytes (limit ${limits.maxFileBytes})")
            }

            // Local header: where the data starts, and that it stays clear of the directory.
            if (localOffset + 30 > cdOffset) refuse(ZipRefusal.CORRUPT, "local header out of bounds")
            val local = readFully(source, localOffset, 30)
            if (le32(local, 0) != SIG_LOCAL) refuse(ZipRefusal.CORRUPT, "bad local header")
            val dataStart = localOffset + 30 + le16(local, 26) + le16(local, 28)
            if (dataStart + csize > cdOffset) refuse(ZipRefusal.CORRUPT, "entry data out of bounds")
            if (method == 0 && csize != usize) refuse(ZipRefusal.CORRUPT, "stored sizes differ")
            spans.add(longArrayOf(localOffset, dataStart + csize))

            val junk = comps.any { it == "__MACOSX" } || comps.lastOrNull() == ".DS_Store"
            when {
                comps.isEmpty() || (isLink && !isDir) || junk -> if (!isDir) skipped++
                else -> {
                    if (!isDir) {
                        declared += usize
                        if (declared > limits.maxTransferBytes) {
                            refuse(ZipRefusal.TOO_LARGE, "archive exceeds ${limits.maxTransferBytes} bytes")
                        }
                    }
                    raws.add(Raw(comps, isDir, usize, method, crc, csize, dataStart))
                }
            }
            p = next
        }

        spans.sortBy { it[0] }
        for (k in 1 until spans.size) {
            if (spans[k][0] < spans[k - 1][1]) refuse(ZipRefusal.OVERLAP, "entries share bytes")
        }

        // Root: one shared top folder becomes the root; otherwise a folder named after the archive.
        val first = raws.firstOrNull()?.comps?.first()
        val shared = first != null &&
            raws.any { !it.isDir } &&
            raws.all { it.comps.first() == first && (it.isDir || it.comps.size >= 2) }
        val root: String
        val placed: List<Raw>
        if (shared) {
            root = first!!
            placed = raws.map { Raw(it.comps.drop(1), it.isDir, it.size, it.method, it.crc, it.compressedSize, it.dataStart) }
        } else {
            val base = archiveName.let { if (it.lowercase(Locale.ROOT).endsWith(".zip")) it.dropLast(4) else it }
            root = component(base.substringAfterLast('/').substringAfterLast('\\'), fallback = "Folder")
            placed = raws
        }

        val taken = HashMap<String, Boolean>() // folded path -> is a file
        val files = ArrayList<FileEntry>()
        val dirs = ArrayList<String>()
        for (r in placed) {
            if (r.comps.isEmpty()) continue
            val dirCount = if (r.isDir) r.comps.size else r.comps.size - 1
            for (k in 1..dirCount) {
                val key = fold(r.comps.subList(0, k).joinToString("/"))
                if (taken[key] == true) refuse(ZipRefusal.CONFLICT, "a file and a folder share a name")
                taken[key] = false
            }
            if (r.isDir) {
                val path = r.comps.joinToString("/")
                if (path !in dirs) dirs.add(path)
                continue
            }
            val parent = r.comps.dropLast(1)
            var leaf = r.comps.last()
            var key = fold((parent + leaf).joinToString("/"))
            if (taken[key] == false) refuse(ZipRefusal.CONFLICT, "a file and a folder share a name")
            var copy = 2
            while (taken.containsKey(key)) {
                leaf = numbered(r.comps.last(), copy++)
                key = fold((parent + leaf).joinToString("/"))
            }
            taken[key] = true
            files.add(FileEntry((parent + leaf).joinToString("/"), r.size, r.method, r.crc, r.compressedSize, r.dataStart))
        }

        val free = limits.freeBytes
        if (free != null) {
            val available = free()
            if (declared + limits.freeSpaceMarginBytes > available) {
                throw UnsafeArchiveException(ZipRefusal.NO_SPACE, "need $declared bytes plus margin, $available available")
            }
        }
        return Plan(root, files, dirs, skipped)
    }

    /**
     * Writes every file in [plan] through [open]. Each sink receives at most the declared size;
     * a short, long or corrupt entry throws CORRUPT. The caller deletes what it wrote on failure.
     */
    fun extract(source: ZipSource, plan: Plan, open: (FileEntry) -> OutputStream) {
        val buf = ByteArray(64 * 1024)
        val outBuf = ByteArray(64 * 1024)
        for (f in plan.files) {
            open(f).use { out ->
                val crc = CRC32()
                var written = 0L
                fun emit(b: ByteArray, n: Int) {
                    if (written + n > f.size) refuse(ZipRefusal.CORRUPT, "${f.path} is longer than declared")
                    out.write(b, 0, n)
                    crc.update(b, 0, n)
                    written += n
                }
                var pos = f.dataStart
                var left = f.compressedSize
                if (f.method == 0) {
                    while (left > 0) {
                        val n = source.readAt(pos, buf, 0, minOf(left, buf.size.toLong()).toInt())
                        if (n <= 0) refuse(ZipRefusal.CORRUPT, "${f.path} is truncated")
                        emit(buf, n)
                        pos += n
                        left -= n
                    }
                } else {
                    val inflater = Inflater(true)
                    try {
                        while (!inflater.finished()) {
                            if (inflater.needsInput()) {
                                // No padding byte past the span: zlib does not need one, and a
                                // made-up byte could complete a truncated stream.
                                if (left <= 0) refuse(ZipRefusal.CORRUPT, "${f.path} is truncated")
                                val n = source.readAt(pos, buf, 0, minOf(left, buf.size.toLong()).toInt())
                                if (n <= 0) refuse(ZipRefusal.CORRUPT, "${f.path} is truncated")
                                inflater.setInput(buf, 0, n)
                                pos += n
                                left -= n
                            }
                            val n = try {
                                inflater.inflate(outBuf)
                            } catch (e: DataFormatException) {
                                refuse(ZipRefusal.CORRUPT, "${f.path}: ${e.message}")
                            }
                            if (n > 0) emit(outBuf, n)
                            else if (inflater.needsDictionary()) refuse(ZipRefusal.CORRUPT, "${f.path} needs a dictionary")
                            else if (!inflater.finished() && !inflater.needsInput()) refuse(ZipRefusal.CORRUPT, "${f.path} stalled")
                        }
                    } finally {
                        inflater.end()
                    }
                }
                if (written != f.size) refuse(ZipRefusal.CORRUPT, "${f.path} is shorter than declared")
                if (crc.value != f.crc) refuse(ZipRefusal.CORRUPT, "${f.path} fails its CRC")
            }
        }
    }

    // ---- paths (section 12.3) ----

    private fun decodeName(b: ByteArray, at: Int, len: Int): String {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(b, at, len)).toString()
        } catch (e: CharacterCodingException) {
            String(b, at, len, Charsets.ISO_8859_1)
        }
    }

    private val DRIVE = Regex("^[A-Za-z]:")

    /** Validated, sanitized components; throws on anything that could leave the root. */
    private fun splitPath(name: String): List<String> {
        if (name.any { it.code < 0x20 || it.code == 0x7F }) refuse(ZipRefusal.UNSAFE_PATH, "control character in name")
        if (name.startsWith("/")) refuse(ZipRefusal.UNSAFE_PATH, "absolute path")
        if (DRIVE.containsMatchIn(name)) refuse(ZipRefusal.UNSAFE_PATH, "drive letter")
        val out = ArrayList<String>()
        for (raw in name.split('/')) {
            if (raw.isEmpty() || raw == ".") continue
            val compat = Normalizer.normalize(raw, Normalizer.Form.NFKC)
            if (compat == "..") refuse(ZipRefusal.UNSAFE_PATH, "parent reference")
            if (compat.contains('/') || compat.contains('\\')) refuse(ZipRefusal.UNSAFE_PATH, "separator in name")
            if (compat == ".") continue
            out.add(component(raw, fallback = "_"))
        }
        if (out.size > MAX_DEPTH) refuse(ZipRefusal.TOO_DEEP, "${out.size} levels (limit $MAX_DEPTH)")
        return out
    }

    private const val RESERVED = "\"*:<>?|"

    /** NFC, reserved characters to '_', no trailing dots or spaces, no leading spaces, capped. */
    private fun component(raw: String, fallback: String): String {
        var s = Normalizer.normalize(raw, Normalizer.Form.NFC)
        s = buildString(s.length) { for (c in s) append(if (c in RESERVED) '_' else c) }
        s = s.trimEnd { it == ' ' || it == '.' }.trimStart { it == ' ' }
        if (s.isEmpty()) return fallback
        return cap(s)
    }

    private fun cap(name: String): String {
        if (name.toByteArray(Charsets.UTF_8).size <= MAX_COMPONENT_BYTES) return name
        val dot = name.lastIndexOf('.')
        val ext = if (dot > 0 && name.length - dot <= 16) name.substring(dot) else ""
        val budget = MAX_COMPONENT_BYTES - ext.toByteArray(Charsets.UTF_8).size
        val stem = name.substring(0, name.length - ext.length)
        val base = StringBuilder()
        var used = 0
        var i = 0
        while (i < stem.length) {
            val cp = stem.codePointAt(i)
            val bytes = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (used + bytes > budget) break
            base.appendCodePoint(cp)
            used += bytes
            i += Character.charCount(cp)
        }
        return base.toString().trimEnd(' ', '.').ifEmpty { "_" } + ext
    }

    /** "name.ext" -> "name (n).ext"; a leading-dot name keeps its dot. */
    private fun numbered(leaf: String, n: Int): String {
        val dot = leaf.lastIndexOf('.')
        return if (dot > 0) "${leaf.substring(0, dot)} ($n)${leaf.substring(dot)}" else "$leaf ($n)"
    }

    private fun fold(path: String): String = path.lowercase(Locale.ROOT)

    // ---- bytes ----

    private fun refuse(reason: ZipRefusal, detail: String): Nothing = throw UnsafeArchiveException(reason, detail)

    private fun readFully(source: ZipSource, pos: Long, len: Int): ByteArray {
        val b = ByteArray(len)
        var got = 0
        while (got < len) {
            val n = source.readAt(pos + got, b, got, len - got)
            if (n <= 0) refuse(ZipRefusal.CORRUPT, "unexpected end of archive")
            got += n
        }
        return b
    }

    private fun le16(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
    private fun le32(b: ByteArray, at: Int): Long = le16(b, at).toLong() or (le16(b, at + 2).toLong() shl 16)
    private fun le64(b: ByteArray, at: Int): Long = ByteBuffer.wrap(b, at, 8).order(ByteOrder.LITTLE_ENDIAN).long
}
