package com.tayson.rockflash.media.scsi

import com.tayson.rockflash.usb.BulkOnlyProtocol

/**
 * Construtores e parsers SCSI usados por unidades USB Mass Storage.
 *
 * READ/WRITE(16) e READ CAPACITY(16) evitam o limite de 2 TiB associado aos
 * comandos de 10 bytes e a contadores de LBA de 32 bits.
 */
object ScsiProtocol {
    const val USB_CLASS_MASS_STORAGE = 0x08
    const val SUBCLASS_RBC = 0x01
    const val SUBCLASS_ATAPI = 0x02
    const val SUBCLASS_UFI = 0x04
    const val SUBCLASS_SCSI_TRANSPARENT = 0x06
    const val PROTOCOL_CBI = 0x00
    const val PROTOCOL_CBI_NO_INTERRUPT = 0x01
    const val PROTOCOL_BULK_ONLY = 0x50
    const val PROTOCOL_UAS = 0x62

    private const val OP_TEST_UNIT_READY = 0x00
    private const val OP_INQUIRY = 0x12
    private const val OP_READ_CAPACITY_10 = 0x25
    private const val OP_READ_10 = 0x28
    private const val OP_WRITE_10 = 0x2A
    private const val OP_SERVICE_ACTION_IN_16 = 0x9E
    private const val SERVICE_ACTION_READ_CAPACITY_16 = 0x10
    private const val OP_READ_16 = 0x88
    private const val OP_WRITE_16 = 0x8A

    data class Inquiry(
        val peripheralType: Int,
        val removable: Boolean,
        val vendor: String,
        val product: String,
        val revision: String,
    )

    data class Capacity(
        val blockCount: ULong,
        val blockSize: Int,
    ) {
        init {
            require(blockCount > 0uL) { "Quantidade de blocos inválida" }
            require(blockSize > 0) { "Tamanho de bloco inválido" }
        }

        val totalBytes: ULong
            get() = blockCount * blockSize.toULong()
    }

    fun testUnitReady(): ByteArray = ByteArray(6).also {
        it[0] = OP_TEST_UNIT_READY.toByte()
    }

    fun inquiry(allocationLength: Int = 36): ByteArray {
        require(allocationLength in 1..0xFF)
        return ByteArray(6).also {
            it[0] = OP_INQUIRY.toByte()
            it[4] = allocationLength.toByte()
        }
    }

    fun parseInquiry(response: ByteArray): Inquiry {
        require(response.size >= 36) { "Resposta INQUIRY incompleta" }
        return Inquiry(
            peripheralType = response[0].toInt() and 0x1F,
            removable = response[1].toInt() and 0x80 != 0,
            vendor = response.decodeAscii(8, 16),
            product = response.decodeAscii(16, 32),
            revision = response.decodeAscii(32, 36),
        )
    }

    fun readCapacity10(): ByteArray = ByteArray(10).also {
        it[0] = OP_READ_CAPACITY_10.toByte()
    }

    fun parseCapacity10(response: ByteArray): Capacity? {
        require(response.size >= 8) { "Resposta READ CAPACITY(10) incompleta" }
        val lastLba = BulkOnlyProtocol.getBe32(response, 0)
        if (lastLba == 0xFFFF_FFFFL) return null
        val blockSize = BulkOnlyProtocol.getBe32(response, 4).toInt()
        return Capacity(blockCount = lastLba.toULong() + 1uL, blockSize = blockSize)
    }

    fun readCapacity16(allocationLength: Int = 32): ByteArray {
        require(allocationLength in 12..0xFFFF)
        return ByteArray(16).also {
            it[0] = OP_SERVICE_ACTION_IN_16.toByte()
            it[1] = SERVICE_ACTION_READ_CAPACITY_16.toByte()
            BulkOnlyProtocol.putBe32(it, 10, allocationLength.toLong())
        }
    }

    fun parseCapacity16(response: ByteArray): Capacity {
        require(response.size >= 12) { "Resposta READ CAPACITY(16) incompleta" }
        val lastLba = BulkOnlyProtocol.getBe64(response, 0)
        val blockSize = BulkOnlyProtocol.getBe32(response, 8).toInt()
        return Capacity(blockCount = lastLba + 1uL, blockSize = blockSize)
    }

    fun read(lba: ULong, blocks: Int): ByteArray =
        if (lba <= 0xFFFF_FFFFuL && blocks <= 0xFFFF) read10(lba.toLong(), blocks)
        else read16(lba, blocks)

    fun write(lba: ULong, blocks: Int): ByteArray =
        if (lba <= 0xFFFF_FFFFuL && blocks <= 0xFFFF) write10(lba.toLong(), blocks)
        else write16(lba, blocks)

    fun read10(lba: Long, blocks: Int): ByteArray {
        require(lba in 0..0xFFFF_FFFFL)
        require(blocks in 1..0xFFFF)
        return ByteArray(10).also {
            it[0] = OP_READ_10.toByte()
            BulkOnlyProtocol.putBe32(it, 2, lba)
            BulkOnlyProtocol.putBe16(it, 7, blocks)
        }
    }

    fun write10(lba: Long, blocks: Int): ByteArray {
        require(lba in 0..0xFFFF_FFFFL)
        require(blocks in 1..0xFFFF)
        return ByteArray(10).also {
            it[0] = OP_WRITE_10.toByte()
            BulkOnlyProtocol.putBe32(it, 2, lba)
            BulkOnlyProtocol.putBe16(it, 7, blocks)
        }
    }

    fun read16(lba: ULong, blocks: Int): ByteArray {
        require(blocks > 0)
        return ByteArray(16).also {
            it[0] = OP_READ_16.toByte()
            BulkOnlyProtocol.putBe64(it, 2, lba)
            BulkOnlyProtocol.putBe32(it, 10, blocks.toLong())
        }
    }

    fun write16(lba: ULong, blocks: Int): ByteArray {
        require(blocks > 0)
        return ByteArray(16).also {
            it[0] = OP_WRITE_16.toByte()
            BulkOnlyProtocol.putBe64(it, 2, lba)
            BulkOnlyProtocol.putBe32(it, 10, blocks.toLong())
        }
    }

    private fun ByteArray.decodeAscii(start: Int, end: Int): String =
        copyOfRange(start, end).toString(Charsets.US_ASCII).trim('\u0000', ' ')
}
