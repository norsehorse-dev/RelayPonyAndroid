package com.relaypony.session.wan

import com.relaypony.session.InsufficientSpaceException
import com.relaypony.session.TransferLimits
import com.relaypony.session.TransferTooLargeException
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class WanSpoolTests {
    @TempDir lateinit var dir: File

    @Test
    fun spoolsToDiskAndReadsBack() {
        val spool = WanSpool.create(dir, TransferLimits.DEFAULT)
        val a = ByteArray(70_000) { it.toByte() }
        val b = ByteArray(10) { 7 }
        spool.write(a); spool.write(b)
        val f = spool.finish()
        assertArrayEquals(a + b, f.readBytes())
        assertEquals(70_010L, spool.size)
    }

    @Test
    fun capTripsOnSpooledBytes() {
        val limits = TransferLimits(maxTransferBytes = 0)
        val spool = WanSpool.create(dir, limits)
        val cap = WanSpool.maxSpoolBytes(0)
        assertThrows(TransferTooLargeException::class.java) {
            spool.write(ByteArray(cap.toInt() + 1))
        }
        spool.discard()
        assertFalse(spool.file.exists())
    }

    @Test
    fun freeSpaceMarginTrips() {
        val limits = TransferLimits(freeSpaceMarginBytes = 1_000, freeBytes = { 500 })
        val spool = WanSpool.create(dir, limits)
        assertThrows(InsufficientSpaceException::class.java) {
            spool.write(ByteArray(TransferLimits.FREE_SPACE_RECHECK_BYTES.toInt()))
        }
        spool.discard()
    }

    @Test
    fun sweepRemovesLeftoverSpoolsOnly() {
        File(dir, "rx-1.spool").writeText("x")
        File(dir, "keep.txt").writeText("x")
        WanSpool.sweep(dir)
        assertFalse(File(dir, "rx-1.spool").exists())
        assertEquals(true, File(dir, "keep.txt").exists())
    }
}
