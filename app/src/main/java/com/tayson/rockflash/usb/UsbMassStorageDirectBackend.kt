package com.tayson.rockflash.usb

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import java.io.Closeable
import java.io.File
import java.io.IOException
import kotlin.math.ceil

/**
 * Backend autônomo para pendrives USB Mass Storage.
 *
 * Implementa Bulk-Only Transport + SCSI diretamente sobre UsbDeviceConnection,
 * sem /dev/sdX, root, dd ou Termux.
 */
class UsbMassStorageDirectBackend(context: Context) {
    private val usbManager = context.applicationContext
        .getSystemService(Context.USB_SERVICE) as UsbManager

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    fun open(device: UsbDevice): Session = Session(
        device = device,
        transport = AndroidBulkOnlyTransport.open(usbManager, device) { usbInterface ->
            usbInterface.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE &&
                usbInterface.interfaceSubclass == SCSI_TRANSPARENT_SUBCLASS &&
                usbInterface.interfaceProtocol == BULK_ONLY_PROTOCOL
        },
    )

    class Session internal constructor(
        private val device: UsbDevice,
        private val transport: AndroidBulkOnlyTransport,
    ) : Closeable {
        fun inspect(): UsbMassStorageTarget {
            testUnitReady()
            val inquiry = inquiry()
            val capacity = readCapacity()
            return UsbMassStorageTarget(
                deviceName = device.deviceName,
                vendorId = device.vendorId,
                productId = device.productId,
                manufacturer = ascii(inquiry, 8, 8),
                product = ascii(inquiry, 16, 16),
                revision = ascii(inquiry, 32, 4),
                blockSize = capacity.blockSize,
                blockCount = capacity.blockCount,
            )
        }

        fun writeImage(
            image: File,
            verify: Boolean = true,
            onProgress: (MassStorageProgress) -> Unit = {},
        ) {
            require(image.isFile && image.length() > 0L) { "Imagem inexistente ou vazia" }
            val capacity = readCapacity()
            val blockSize = capacity.blockSize
            require(blockSize in 512..65536 && blockSize.countOneBits() == 1) {
                "Tamanho de bloco SCSI não suportado: $blockSize"
            }
            val blocksNeeded = ceil(image.length().toDouble() / blockSize.toDouble()).toLong()
            require(blocksNeeded <= capacity.blockCount) {
                "A imagem (${image.length()} bytes) é maior que o pendrive (${capacity.sizeBytes} bytes)"
            }
            require(blocksNeeded <= 0x1_0000_0000L) {
                "Destino requer WRITE(16), ainda não liberado nesta versão"
            }

            val maxBlocks = minOf(MAX_BLOCKS_PER_COMMAND, maxOf(1, MAX_TRANSFER_BYTES / blockSize))
            val buffer = ByteArray(maxBlocks * blockSize)
            image.inputStream().buffered(MAX_TRANSFER_BYTES).use { input ->
                var lba = 0L
                var consumed = 0L
                while (consumed < image.length()) {
                    buffer.fill(0)
                    val sourceBytes = minOf(buffer.size.toLong(), image.length() - consumed).toInt()
                    readFully(input, buffer, sourceBytes)
                    val blocks = ((sourceBytes + blockSize - 1) / blockSize)
                    val payload = if (blocks * blockSize == buffer.size) buffer else buffer.copyOf(blocks * blockSize)
                    write10(lba, blocks, payload)
                    lba += blocks
                    consumed += sourceBytes
                    onProgress(MassStorageProgress(ProgressPhase.WRITING, consumed, image.length()))
                }
            }
            synchronizeCache()
            if (verify) verifyImage(image, blockSize, maxBlocks, onProgress)
        }

        fun readBlocks(lba: Long, blockCount: Int, blockSize: Int): ByteArray {
            require(lba in 0..0xFFFF_FFFFL)
            require(blockCount in 1..0xFFFF)
            val cdb = ByteArray(10).also {
                it[0] = SCSI_READ_10.toByte()
                BulkOnlyProtocol.putBe32(it, 2, lba)
                BulkOnlyProtocol.putBe16(it, 7, blockCount)
            }
            return transport.executeIn(
                command = cdb,
                commandLength = 10,
                expectedLength = Math.multiplyExact(blockCount, blockSize),
                timeoutMs = DATA_TIMEOUT_MS,
            )
        }

        private fun verifyImage(
            image: File,
            blockSize: Int,
            maxBlocks: Int,
            onProgress: (MassStorageProgress) -> Unit,
        ) {
            val expected = ByteArray(maxBlocks * blockSize)
            image.inputStream().buffered(MAX_TRANSFER_BYTES).use { input ->
                var lba = 0L
                var consumed = 0L
                while (consumed < image.length()) {
                    expected.fill(0)
                    val sourceBytes = minOf(expected.size.toLong(), image.length() - consumed).toInt()
                    readFully(input, expected, sourceBytes)
                    val blocks = (sourceBytes + blockSize - 1) / blockSize
                    val actual = readBlocks(lba, blocks, blockSize)
                    val comparedBytes = blocks * blockSize
                    for (index in 0 until comparedBytes) {
                        if (expected[index] != actual[index]) {
                            throw IOException(
                                "Readback do pendrive falhou no byte ${consumed + index} (LBA ${lba + index / blockSize})",
                            )
                        }
                    }
                    lba += blocks
                    consumed += sourceBytes
                    onProgress(MassStorageProgress(ProgressPhase.VERIFYING, consumed, image.length()))
                }
            }
        }

        private fun testUnitReady() {
            val cdb = ByteArray(6).also { it[0] = SCSI_TEST_UNIT_READY.toByte() }
            transport.executeOut(command = cdb, commandLength = 6)
        }

        private fun inquiry(): ByteArray {
            val cdb = ByteArray(6).also {
                it[0] = SCSI_INQUIRY.toByte()
                it[4] = INQUIRY_LENGTH.toByte()
            }
            return transport.executeIn(cdb, commandLength = 6, expectedLength = INQUIRY_LENGTH)
        }

        private fun readCapacity(): Capacity {
            val cdb10 = ByteArray(10).also { it[0] = SCSI_READ_CAPACITY_10.toByte() }
            val response10 = transport.executeIn(cdb10, commandLength = 10, expectedLength = 8)
            val lastLba10 = BulkOnlyProtocol.getBe32(response10, 0)
            val blockSize10 = BulkOnlyProtocol.getBe32(response10, 4).toInt()
            if (lastLba10 != 0xFFFF_FFFFL) {
                return Capacity(lastLba10 + 1, blockSize10)
            }

            val cdb16 = ByteArray(16).also {
                it[0] = SCSI_SERVICE_ACTION_IN_16.toByte()
                it[1] = SCSI_READ_CAPACITY_16_SERVICE_ACTION.toByte()
                BulkOnlyProtocol.putBe32(it, 10, READ_CAPACITY_16_LENGTH.toLong())
            }
            val response16 = transport.executeIn(
                cdb16,
                commandLength = 16,
                expectedLength = READ_CAPACITY_16_LENGTH,
            )
            val lastLba16 = BulkOnlyProtocol.getBe64(response16, 0)
            require(lastLba16 < Long.MAX_VALUE.toULong()) { "Capacidade SCSI excede o limite do aplicativo" }
            val blockSize16 = BulkOnlyProtocol.getBe32(response16, 8).toInt()
            return Capacity(lastLba16.toLong() + 1, blockSize16)
        }

        private fun write10(lba: Long, blocks: Int, payload: ByteArray) {
            require(lba in 0..0xFFFF_FFFFL)
            require(blocks in 1..0xFFFF)
            val cdb = ByteArray(10).also {
                it[0] = SCSI_WRITE_10.toByte()
                BulkOnlyProtocol.putBe32(it, 2, lba)
                BulkOnlyProtocol.putBe16(it, 7, blocks)
            }
            transport.executeOut(
                command = cdb,
                commandLength = 10,
                payload = payload,
                timeoutMs = DATA_TIMEOUT_MS,
            )
        }

        private fun synchronizeCache() {
            val cdb = ByteArray(10).also { it[0] = SCSI_SYNCHRONIZE_CACHE_10.toByte() }
            transport.executeOut(command = cdb, commandLength = 10, timeoutMs = DATA_TIMEOUT_MS)
        }

        override fun close() = transport.close()
    }

    data class UsbMassStorageTarget(
        val deviceName: String,
        val vendorId: Int,
        val productId: Int,
        val manufacturer: String,
        val product: String,
        val revision: String,
        val blockSize: Int,
        val blockCount: Long,
    ) {
        val sizeBytes: Long
            get() = Math.multiplyExact(blockCount, blockSize.toLong())

        val label: String
            get() = buildString {
                append("%04x:%04x".format(vendorId, productId))
                listOf(manufacturer, product, revision).filter { it.isNotBlank() }.forEach {
                    append(" • ").append(it)
                }
                append(" • ").append(formatBytes(sizeBytes))
                append(" • ").append(deviceName)
            }
    }

    data class MassStorageProgress(
        val phase: ProgressPhase,
        val completedBytes: Long,
        val totalBytes: Long,
    )

    enum class ProgressPhase {
        WRITING,
        VERIFYING,
    }

    private data class Capacity(
        val blockCount: Long,
        val blockSize: Int,
    ) {
        val sizeBytes: Long
            get() = Math.multiplyExact(blockCount, blockSize.toLong())
    }

    companion object {
        private const val SCSI_TRANSPARENT_SUBCLASS = 0x06
        private const val BULK_ONLY_PROTOCOL = 0x50
        private const val INQUIRY_LENGTH = 36
        private const val READ_CAPACITY_16_LENGTH = 32
        private const val MAX_TRANSFER_BYTES = 1024 * 1024
        private const val MAX_BLOCKS_PER_COMMAND = 128
        private const val DATA_TIMEOUT_MS = 120_000

        private const val SCSI_TEST_UNIT_READY = 0x00
        private const val SCSI_INQUIRY = 0x12
        private const val SCSI_READ_CAPACITY_10 = 0x25
        private const val SCSI_READ_10 = 0x28
        private const val SCSI_WRITE_10 = 0x2A
        private const val SCSI_SYNCHRONIZE_CACHE_10 = 0x35
        private const val SCSI_SERVICE_ACTION_IN_16 = 0x9E
        private const val SCSI_READ_CAPACITY_16_SERVICE_ACTION = 0x10

        private fun ascii(bytes: ByteArray, offset: Int, length: Int): String =
            bytes.copyOfRange(offset, minOf(bytes.size, offset + length))
                .toString(Charsets.US_ASCII)
                .trim()

        private fun readFully(stream: java.io.InputStream, buffer: ByteArray, length: Int) {
            var offset = 0
            while (offset < length) {
                val read = stream.read(buffer, offset, length - offset)
                if (read < 0) throw IOException("Fim inesperado da imagem")
                offset += read
            }
        }

        private fun formatBytes(bytes: Long): String = when {
            bytes >= 1024L * 1024L * 1024L -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024L * 1024L -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
            else -> "$bytes B"
        }
    }
}
