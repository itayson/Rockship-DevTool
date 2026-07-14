package com.tayson.rockflash.media.scsi

import com.tayson.rockflash.usb.BulkOnlyProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScsiProtocolTest {
    @Test
    fun `capacity10 parses block count and size`() {
        val response = ByteArray(8)
        BulkOnlyProtocol.putBe32(response, 0, 0x001F_FFFFL)
        BulkOnlyProtocol.putBe32(response, 4, 512)

        val capacity = ScsiProtocol.parseCapacity10(response)!!

        assertEquals(0x0020_0000uL, capacity.blockCount)
        assertEquals(512, capacity.blockSize)
    }

    @Test
    fun `capacity10 requests capacity16 when sentinel is returned`() {
        val response = ByteArray(8)
        BulkOnlyProtocol.putBe32(response, 0, 0xFFFF_FFFFL)
        BulkOnlyProtocol.putBe32(response, 4, 512)

        assertNull(ScsiProtocol.parseCapacity10(response))
    }

    @Test
    fun `capacity16 supports media larger than two tebibytes`() {
        val response = ByteArray(32)
        BulkOnlyProtocol.putBe64(response, 0, 0x1_0000_0000uL)
        BulkOnlyProtocol.putBe32(response, 8, 4096)

        val capacity = ScsiProtocol.parseCapacity16(response)

        assertEquals(0x1_0000_0001uL, capacity.blockCount)
        assertEquals(4096, capacity.blockSize)
        assertTrue(capacity.totalBytes > 2uL * 1024uL * 1024uL * 1024uL * 1024uL)
    }

    @Test
    fun `read16 serializes 64 bit LBA`() {
        val cdb = ScsiProtocol.read16(0x1_2345_6789uL, 0x10000)

        assertEquals(0x88, cdb[0].toInt() and 0xFF)
        assertEquals(0x1_2345_6789uL, BulkOnlyProtocol.getBe64(cdb, 2))
        assertEquals(0x10000L, BulkOnlyProtocol.getBe32(cdb, 10))
    }

    @Test
    fun `small requests use read10`() {
        val cdb = ScsiProtocol.read(1024uL, 32)

        assertEquals(10, cdb.size)
        assertEquals(0x28, cdb[0].toInt() and 0xFF)
    }
}
