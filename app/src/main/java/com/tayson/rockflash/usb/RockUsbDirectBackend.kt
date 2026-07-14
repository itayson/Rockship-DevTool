package com.tayson.rockflash.usb

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import java.io.Closeable
import java.io.File
import java.io.IOException

/**
 * Implementação interna do protocolo RockUSB usado pelo rkdeveloptool.
 *
 * Esta classe conversa diretamente com a interface vendor 0xff/6/5 por
 * UsbDeviceConnection. Portanto, leitura, backup e gravação LBA não precisam
 * de root, Termux, su ou executável externo.
 */
class RockUsbDirectBackend(context: Context) {
    private val usbManager = context.applicationContext
        .getSystemService(Context.USB_SERVICE) as UsbManager

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    fun open(device: UsbDevice): Session {
        require(device.vendorId == RockchipUsbController.ROCKCHIP_VENDOR_ID) {
            "Dispositivo não é Rockchip: %04x:%04x".format(device.vendorId, device.productId)
        }
        return Session(
            AndroidBulkOnlyTransport.open(usbManager, device) { usbInterface ->
                usbInterface.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC &&
                    usbInterface.interfaceSubclass == 6 &&
                    usbInterface.interfaceProtocol == 5
            },
        )
    }

    class Session internal constructor(
        private val transport: AndroidBulkOnlyTransport,
    ) : Closeable {
        fun readChipInfo(): ByteArray = readSimple(OP_READ_CHIP_INFO, 16)

        fun readFlashId(): ByteArray = readSimple(OP_READ_FLASH_ID, 5)

        fun readCapability(): ByteArray = readSimple(OP_READ_CAPABILITY, 8)

        fun readStorage(): Int {
            val raw = readSimple(OP_READ_STORAGE, 4)
            val bits = getLe32(raw, 0)
            return (0 until 32).firstOrNull { bits and (1L shl it) != 0L } ?: 255
        }

        fun readFlashInfo(): RockUsbFlashInfo {
            val command = rockCommand(OP_READ_FLASH_INFO)
            val raw = transport.executeIn(
                command = command,
                commandLength = 6,
                expectedLength = 512,
                allowShortPacket = true,
            )
            if (raw.size < FLASH_INFO_MINIMUM) {
                throw IOException("Resposta de flash incompleta: ${raw.size} bytes")
            }
            return RockUsbFlashInfo(
                raw = raw,
                sizeSectors = getLe32(raw, 0),
                accessTime = raw[5].toInt() and 0xFF,
                blockSizeSectors = getLe16(raw, 6),
                pageSizeSectors = raw[8].toInt() and 0xFF,
                eccBits = raw[9].toInt() and 0xFF,
                flashCsMask = raw[10].toInt() and 0xFF,
            )
        }

        fun readLba(startSector: Long, sectorCount: Int): ByteArray {
            require(startSector in 0..0xFFFF_FFFFL) { "LBA inicial fora do limite RockUSB" }
            require(sectorCount in 1..MAX_SECTORS_PER_COMMAND) {
                "Quantidade de setores deve estar entre 1 e $MAX_SECTORS_PER_COMMAND"
            }
            val command = rockCommand(
                opcode = OP_READ_LBA,
                address = startSector,
                sectorCount = sectorCount,
                subCode = RW_METHOD_IMAGE,
            )
            return transport.executeIn(
                command = command,
                commandLength = 10,
                expectedLength = sectorCount * SECTOR_SIZE,
                timeoutMs = DATA_TIMEOUT_MS,
            )
        }

        fun writeLba(startSector: Long, payload: ByteArray) {
            require(startSector in 0..0xFFFF_FFFFL) { "LBA inicial fora do limite RockUSB" }
            require(payload.isNotEmpty() && payload.size % SECTOR_SIZE == 0) {
                "O bloco RockUSB deve ser múltiplo de $SECTOR_SIZE bytes"
            }
            val sectors = payload.size / SECTOR_SIZE
            require(sectors <= MAX_SECTORS_PER_COMMAND) {
                "Bloco excede $MAX_SECTORS_PER_COMMAND setores"
            }
            val command = rockCommand(
                opcode = OP_WRITE_LBA,
                address = startSector,
                sectorCount = sectors,
                subCode = RW_METHOD_IMAGE,
            )
            transport.executeOut(
                command = command,
                commandLength = 10,
                payload = payload,
                timeoutMs = DATA_TIMEOUT_MS,
            )
        }

        fun backupToFile(
            output: File,
            totalSectors: Long,
            onProgress: (completedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
        ) {
            require(totalSectors in 1..0x1_0000_0000L) { "Tamanho de backup inválido" }
            output.parentFile?.mkdirs()
            val totalBytes = Math.multiplyExact(totalSectors, SECTOR_SIZE.toLong())
            output.outputStream().buffered(BUFFER_SIZE).use { stream ->
                var sector = 0L
                while (sector < totalSectors) {
                    val count = minOf(MAX_SECTORS_PER_COMMAND.toLong(), totalSectors - sector).toInt()
                    val data = readLba(sector, count)
                    stream.write(data)
                    sector += count
                    onProgress(sector * SECTOR_SIZE, totalBytes)
                }
                stream.flush()
            }
            if (output.length() != totalBytes) {
                throw IOException("Backup incompleto: esperado=$totalBytes obtido=${output.length()}")
            }
        }

        fun writeFileAtLba(
            image: File,
            startSector: Long,
            verify: Boolean = true,
            onProgress: (completedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
        ) {
            require(image.isFile && image.length() > 0L) { "Imagem inexistente ou vazia" }
            require(image.length() % SECTOR_SIZE == 0L) {
                "Imagem deve possuir tamanho múltiplo de $SECTOR_SIZE bytes"
            }
            val totalSectors = image.length() / SECTOR_SIZE
            require(startSector + totalSectors <= 0x1_0000_0000L) { "Imagem ultrapassa o espaço LBA endereçável" }

            image.inputStream().buffered(BUFFER_SIZE).use { stream ->
                var sector = startSector
                var completed = 0L
                val buffer = ByteArray(MAX_SECTORS_PER_COMMAND * SECTOR_SIZE)
                while (completed < image.length()) {
                    val wanted = minOf(buffer.size.toLong(), image.length() - completed).toInt()
                    readFully(stream, buffer, wanted)
                    val block = if (wanted == buffer.size) buffer else buffer.copyOf(wanted)
                    writeLba(sector, block)
                    sector += wanted / SECTOR_SIZE
                    completed += wanted
                    onProgress(completed, image.length())
                }
            }

            if (verify) verifyFileAtLba(image, startSector, onProgress)
        }

        fun reset(subCode: Int = 0) {
            val command = rockCommand(OP_DEVICE_RESET, subCode = subCode)
            transport.executeOut(command = command, commandLength = 6)
        }

        private fun verifyFileAtLba(
            image: File,
            startSector: Long,
            onProgress: (completedBytes: Long, totalBytes: Long) -> Unit,
        ) {
            image.inputStream().buffered(BUFFER_SIZE).use { stream ->
                var sector = startSector
                var completed = 0L
                val expected = ByteArray(MAX_SECTORS_PER_COMMAND * SECTOR_SIZE)
                while (completed < image.length()) {
                    val wanted = minOf(expected.size.toLong(), image.length() - completed).toInt()
                    readFully(stream, expected, wanted)
                    val actual = readLba(sector, wanted / SECTOR_SIZE)
                    for (index in 0 until wanted) {
                        if (expected[index] != actual[index]) {
                            throw IOException(
                                "Verificação RockUSB falhou no byte ${completed + index} (LBA ${sector + index / SECTOR_SIZE})",
                            )
                        }
                    }
                    sector += wanted / SECTOR_SIZE
                    completed += wanted
                    onProgress(completed, image.length())
                }
            }
        }

        private fun readSimple(opcode: Int, length: Int): ByteArray =
            transport.executeIn(
                command = rockCommand(opcode),
                commandLength = 6,
                expectedLength = length,
            )

        override fun close() = transport.close()
    }

    data class RockUsbFlashInfo(
        val raw: ByteArray,
        val sizeSectors: Long,
        val accessTime: Int,
        val blockSizeSectors: Int,
        val pageSizeSectors: Int,
        val eccBits: Int,
        val flashCsMask: Int,
    ) {
        val sizeBytes: Long
            get() = sizeSectors * SECTOR_SIZE

        val sizeMb: Long
            get() = sizeBytes / (1024L * 1024L)

        val summary: String
            get() = buildString {
                appendLine("Flash Size: $sizeMb MB ($sizeSectors setores)")
                appendLine("Block Size: ${blockSizeSectors * SECTOR_SIZE / 1024} KB")
                appendLine("Page Size: ${pageSizeSectors * SECTOR_SIZE / 1024} KB")
                appendLine("ECC Bits: $eccBits")
                appendLine("Access Time: $accessTime")
                append("Flash CS: 0x${flashCsMask.toString(16)}")
            }
    }

    companion object {
        const val SECTOR_SIZE = 512
        const val MAX_SECTORS_PER_COMMAND = 128
        private const val BUFFER_SIZE = 1024 * 1024
        private const val DATA_TIMEOUT_MS = 120_000
        private const val FLASH_INFO_MINIMUM = 11
        private const val RW_METHOD_IMAGE = 0

        private const val OP_READ_FLASH_ID = 0x01
        private const val OP_READ_LBA = 0x14
        private const val OP_WRITE_LBA = 0x15
        private const val OP_READ_FLASH_INFO = 0x1A
        private const val OP_READ_CHIP_INFO = 0x1B
        private const val OP_READ_STORAGE = 0x2B
        private const val OP_READ_CAPABILITY = 0xAA
        private const val OP_DEVICE_RESET = 0xFF

        internal fun rockCommand(
            opcode: Int,
            address: Long = 0,
            sectorCount: Int = 0,
            subCode: Int = 0,
        ): ByteArray = ByteArray(16).also { command ->
            command[0] = opcode.toByte()
            command[1] = subCode.toByte()
            BulkOnlyProtocol.putBe32(command, 2, address)
            BulkOnlyProtocol.putBe16(command, 7, sectorCount)
        }

        private fun getLe16(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

        private fun getLe32(bytes: ByteArray, offset: Int): Long =
            (bytes[offset].toLong() and 0xFF) or
                ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
                ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
                ((bytes[offset + 3].toLong() and 0xFF) shl 24)

        private fun readFully(stream: java.io.InputStream, buffer: ByteArray, length: Int) {
            var offset = 0
            while (offset < length) {
                val read = stream.read(buffer, offset, length - offset)
                if (read < 0) throw IOException("Fim inesperado do arquivo")
                offset += read
            }
        }
    }
}
