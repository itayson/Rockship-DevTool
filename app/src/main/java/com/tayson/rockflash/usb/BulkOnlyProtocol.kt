package com.tayson.rockflash.usb

/**
 * Serialização do USB Bulk-Only Transport usada tanto por dispositivos
 * RockUSB quanto por unidades SCSI USB. Não depende das classes Android para
 * permitir testes unitários no JVM.
 */
object BulkOnlyProtocol {
    const val CBW_SIZE = 31
    const val CSW_SIZE = 13
    const val CBW_SIGNATURE = 0x43425355
    const val CSW_SIGNATURE = 0x53425355
    const val DIRECTION_OUT = 0x00
    const val DIRECTION_IN = 0x80

    data class CommandStatus(
        val tag: Int,
        val residue: Long,
        val status: Int,
    ) {
        val success: Boolean
            get() = status == 0
    }

    fun buildCommandBlockWrapper(
        tag: Int,
        transferLength: Int,
        directionIn: Boolean,
        lun: Int,
        command: ByteArray,
        commandLength: Int = command.size,
    ): ByteArray {
        require(transferLength >= 0) { "Tamanho de transferência negativo" }
        require(lun in 0..15) { "LUN inválido: $lun" }
        require(commandLength in 1..16) { "CDB deve possuir entre 1 e 16 bytes" }
        require(command.size >= commandLength) { "CDB menor que commandLength" }

        return ByteArray(CBW_SIZE).also { output ->
            putLe32(output, 0, CBW_SIGNATURE.toLong())
            putLe32(output, 4, tag.toLong() and 0xFFFF_FFFFL)
            putLe32(output, 8, transferLength.toLong())
            output[12] = (if (directionIn) DIRECTION_IN else DIRECTION_OUT).toByte()
            output[13] = lun.toByte()
            output[14] = commandLength.toByte()
            command.copyInto(output, destinationOffset = 15, endIndex = commandLength)
        }
    }

    fun parseCommandStatusWrapper(bytes: ByteArray, expectedTag: Int): CommandStatus {
        require(bytes.size >= CSW_SIZE) { "CSW incompleto: ${bytes.size} bytes" }
        val signature = getLe32(bytes, 0).toInt()
        require(signature == CSW_SIGNATURE) {
            "Assinatura CSW inválida: 0x${signature.toUInt().toString(16)}"
        }
        val tag = getLe32(bytes, 4).toInt()
        require(tag == expectedTag) {
            "Tag CSW não corresponde ao comando: esperado=$expectedTag recebido=$tag"
        }
        return CommandStatus(
            tag = tag,
            residue = getLe32(bytes, 8),
            status = bytes[12].toInt() and 0xFF,
        )
    }

    fun putBe16(target: ByteArray, offset: Int, value: Int) {
        require(value in 0..0xFFFF)
        target[offset] = (value ushr 8).toByte()
        target[offset + 1] = value.toByte()
    }

    fun putBe32(target: ByteArray, offset: Int, value: Long) {
        require(value in 0..0xFFFF_FFFFL)
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }

    fun putBe64(target: ByteArray, offset: Int, value: ULong) {
        repeat(8) { index ->
            val shift = (7 - index) * 8
            target[offset + index] = (value shr shift).toByte()
        }
    }

    fun getBe16(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xFF) shl 8) or
            (source[offset + 1].toInt() and 0xFF)

    fun getBe32(source: ByteArray, offset: Int): Long =
        ((source[offset].toLong() and 0xFF) shl 24) or
            ((source[offset + 1].toLong() and 0xFF) shl 16) or
            ((source[offset + 2].toLong() and 0xFF) shl 8) or
            (source[offset + 3].toLong() and 0xFF)

    fun getBe64(source: ByteArray, offset: Int): ULong {
        var value = 0uL
        repeat(8) { index ->
            value = (value shl 8) or (source[offset + index].toULong() and 0xFFuL)
        }
        return value
    }

    private fun putLe32(target: ByteArray, offset: Int, value: Long) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
        target[offset + 2] = (value ushr 16).toByte()
        target[offset + 3] = (value ushr 24).toByte()
    }

    private fun getLe32(source: ByteArray, offset: Int): Long =
        (source[offset].toLong() and 0xFF) or
            ((source[offset + 1].toLong() and 0xFF) shl 8) or
            ((source[offset + 2].toLong() and 0xFF) shl 16) or
            ((source[offset + 3].toLong() and 0xFF) shl 24)
}
