package com.tayson.rockflash.media.scsi

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ScsiRawImageWriterTest {
    @Test
    fun `writes pads and verifies non aligned image`() {
        val source = ByteArray(700) { index -> (index and 0xFF).toByte() }
        val device = FakeScsiBlockDevice(blocks = 16uL, blockSize = 512)
        val progress = mutableListOf<MediaWriteProgress>()

        ScsiRawImageWriter(device).writeAndVerify(
            sourceFactory = { ByteArrayInputStream(source) },
            imageSizeBytes = source.size.toLong(),
            onProgress = progress::add,
        )

        assertArrayEquals(source, device.storage.copyOfRange(0, source.size))
        assertEquals(0, device.storage.copyOfRange(source.size, 1024).count { it != 0.toByte() })
        assertEquals(MediaWritePhase.WRITING, progress.first().phase)
        assertEquals(MediaWritePhase.VERIFYING, progress.last().phase)
        assertEquals(source.size.toLong(), progress.last().completedBytes)
    }

    private class FakeScsiBlockDevice(
        blocks: ULong,
        blockSize: Int,
    ) : ScsiBlockDevice {
        override val inquiry = ScsiProtocol.Inquiry(
            peripheralType = 0,
            removable = true,
            vendor = "TEST",
            product = "MEMORY",
            revision = "1.0",
        )
        override val capacity = ScsiProtocol.Capacity(blocks, blockSize)
        val storage = ByteArray((blocks * blockSize.toULong()).toInt())

        override fun readBlocks(lba: ULong, blocks: Int): ByteArray {
            val start = (lba * capacity.blockSize.toULong()).toInt()
            val end = start + blocks * capacity.blockSize
            return storage.copyOfRange(start, end)
        }

        override fun writeBlocks(lba: ULong, payload: ByteArray) {
            val start = (lba * capacity.blockSize.toULong()).toInt()
            payload.copyInto(storage, start)
        }

        override fun close() = Unit
    }
}
