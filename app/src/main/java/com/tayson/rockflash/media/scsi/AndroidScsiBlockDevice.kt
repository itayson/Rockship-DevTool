package com.tayson.rockflash.media.scsi

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.tayson.rockflash.usb.AndroidBulkOnlyTransport
import java.io.Closeable
import java.io.IOException

interface ScsiBlockDevice : Closeable {
    val inquiry: ScsiProtocol.Inquiry
    val capacity: ScsiProtocol.Capacity

    fun readBlocks(lba: ULong, blocks: Int): ByteArray

    fun writeBlocks(lba: ULong, payload: ByteArray)
}

/**
 * Unidade SCSI sobre USB Mass Storage Bulk-Only Transport.
 *
 * Esta implementação cobre pendrives, leitores SD USB e gabinetes HDD/SSD que
 * exponham SCSI Transparent + BOT. UAS (protocolo 0x62) é detectado em outra
 * camada, mas não é enviado por este transporte.
 */
class AndroidScsiBlockDevice private constructor(
    private val transport: AndroidBulkOnlyTransport,
    private val lun: Int,
) : ScsiBlockDevice {
    override val inquiry: ScsiProtocol.Inquiry
    override val capacity: ScsiProtocol.Capacity

    init {
        transport.executeOut(
            command = ScsiProtocol.testUnitReady(),
            commandLength = 6,
            lun = lun,
        )
        inquiry = ScsiProtocol.parseInquiry(
            transport.executeIn(
                command = ScsiProtocol.inquiry(INQUIRY_LENGTH),
                commandLength = 6,
                expectedLength = INQUIRY_LENGTH,
                lun = lun,
            ),
        )
        val capacity10 = ScsiProtocol.parseCapacity10(
            transport.executeIn(
                command = ScsiProtocol.readCapacity10(),
                commandLength = 10,
                expectedLength = READ_CAPACITY_10_LENGTH,
                lun = lun,
            ),
        )
        capacity = capacity10 ?: ScsiProtocol.parseCapacity16(
            transport.executeIn(
                command = ScsiProtocol.readCapacity16(READ_CAPACITY_16_LENGTH),
                commandLength = 16,
                expectedLength = READ_CAPACITY_16_LENGTH,
                lun = lun,
            ),
        )
    }

    override fun readBlocks(lba: ULong, blocks: Int): ByteArray {
        validateRange(lba, blocks)
        val transferLength = transferLength(blocks)
        return transport.executeIn(
            command = ScsiProtocol.read(lba, blocks),
            commandLength = if (lba <= 0xFFFF_FFFFuL && blocks <= 0xFFFF) 10 else 16,
            expectedLength = transferLength,
            lun = lun,
        )
    }

    override fun writeBlocks(lba: ULong, payload: ByteArray) {
        require(payload.isNotEmpty()) { "Payload SCSI vazio" }
        require(payload.size % capacity.blockSize == 0) {
            "Payload deve estar alinhado ao bloco de ${capacity.blockSize} bytes"
        }
        val blocks = payload.size / capacity.blockSize
        validateRange(lba, blocks)
        transport.executeOut(
            command = ScsiProtocol.write(lba, blocks),
            commandLength = if (lba <= 0xFFFF_FFFFuL && blocks <= 0xFFFF) 10 else 16,
            payload = payload,
            lun = lun,
        )
    }

    private fun validateRange(lba: ULong, blocks: Int) {
        require(blocks > 0) { "Quantidade de blocos deve ser positiva" }
        require(blocks <= MAX_BLOCKS_PER_TRANSFER) {
            "Transferência excede o limite de $MAX_BLOCKS_PER_TRANSFER blocos"
        }
        val endExclusive = lba + blocks.toULong()
        require(endExclusive >= lba && endExclusive <= capacity.blockCount) {
            "Intervalo SCSI fora da mídia: LBA=$lba blocos=$blocks capacidade=${capacity.blockCount}"
        }
        transferLength(blocks)
    }

    private fun transferLength(blocks: Int): Int = try {
        Math.multiplyExact(blocks, capacity.blockSize).also { bytes ->
            require(bytes <= MAX_TRANSFER_BYTES) {
                "Transferência de $bytes bytes excede o limite de $MAX_TRANSFER_BYTES"
            }
        }
    } catch (error: ArithmeticException) {
        throw IllegalArgumentException("Overflow no tamanho da transferência SCSI", error)
    }

    override fun close() {
        transport.close()
    }

    companion object {
        private const val INQUIRY_LENGTH = 36
        private const val READ_CAPACITY_10_LENGTH = 8
        private const val READ_CAPACITY_16_LENGTH = 32
        private const val MAX_TRANSFER_BYTES = 8 * 1024 * 1024
        private const val MAX_BLOCKS_PER_TRANSFER = 0xFFFF

        fun open(
            usbManager: UsbManager,
            device: UsbDevice,
            lun: Int = 0,
        ): AndroidScsiBlockDevice {
            require(lun in 0..15) { "LUN inválido: $lun" }
            val transport = AndroidBulkOnlyTransport.open(
                usbManager = usbManager,
                device = device,
                interfaceMatcher = ::isSupportedBotInterface,
            )
            return try {
                AndroidScsiBlockDevice(transport, lun)
            } catch (error: Exception) {
                transport.close()
                throw IOException("Falha ao inicializar unidade SCSI USB", error)
            }
        }

        fun isSupportedBotInterface(usbInterface: UsbInterface): Boolean =
            usbInterface.interfaceClass == ScsiProtocol.USB_CLASS_MASS_STORAGE &&
                usbInterface.interfaceSubclass == ScsiProtocol.SUBCLASS_SCSI_TRANSPARENT &&
                usbInterface.interfaceProtocol == ScsiProtocol.PROTOCOL_BULK_ONLY
    }
}
