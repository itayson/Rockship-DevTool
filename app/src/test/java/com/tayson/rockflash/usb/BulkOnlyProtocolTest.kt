package com.tayson.rockflash.usb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BulkOnlyProtocolTest {
    @Test
    fun serializesCbwWithLittleEndianHeaderAndCdb() {
        val cdb = byteArrayOf(0x28, 0, 0, 0, 0, 1, 0, 0, 2, 0)
        val cbw = BulkOnlyProtocol.buildCommandBlockWrapper(
            tag = 0x12345678,
            transferLength = 1024,
            directionIn = true,
            lun = 0,
            command = cdb,
            commandLength = 10,
        )

        assertEquals(BulkOnlyProtocol.CBW_SIZE, cbw.size)
        assertArrayEquals(byteArrayOf(0x55, 0x53, 0x42, 0x43), cbw.copyOfRange(0, 4))
        assertArrayEquals(byteArrayOf(0x78, 0x56, 0x34, 0x12), cbw.copyOfRange(4, 8))
        assertArrayEquals(byteArrayOf(0x00, 0x04, 0x00, 0x00), cbw.copyOfRange(8, 12))
        assertEquals(0x80.toByte(), cbw[12])
        assertEquals(10, cbw[14].toInt())
        assertArrayEquals(cdb, cbw.copyOfRange(15, 25))
    }

    @Test
    fun parsesValidCsw() {
        val csw = byteArrayOf(
            0x55, 0x53, 0x42, 0x53,
            0x78, 0x56, 0x34, 0x12,
            0x10, 0x00, 0x00, 0x00,
            0x00,
        )

        val status = BulkOnlyProtocol.parseCommandStatusWrapper(csw, 0x12345678)

        assertTrue(status.success)
        assertEquals(16L, status.residue)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsCswWithWrongTag() {
        val csw = byteArrayOf(
            0x55, 0x53, 0x42, 0x53,
            0x01, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x00,
        )
        BulkOnlyProtocol.parseCommandStatusWrapper(csw, 2)
    }

    @Test
    fun serializesRockUsbAddressAndLengthInBigEndian() {
        val command = RockUsbDirectBackend.rockCommand(
            opcode = 0x15,
            address = 0x12345678,
            sectorCount = 0x9ABC,
            subCode = 0,
        )

        assertEquals(0x15.toByte(), command[0])
        assertArrayEquals(
            byteArrayOf(0x12, 0x34, 0x56, 0x78),
            command.copyOfRange(2, 6),
        )
        assertArrayEquals(
            byteArrayOf(0x9A.toByte(), 0xBC.toByte()),
            command.copyOfRange(7, 9),
        )
    }
}
