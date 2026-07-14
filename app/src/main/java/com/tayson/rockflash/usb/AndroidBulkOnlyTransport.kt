package com.tayson.rockflash.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Transporte Bulk-Only sobre a API USB Host do Android.
 *
 * Usa exclusivamente UsbManager/UsbDeviceConnection. Não acessa /dev/bus/usb,
 * não executa su e não depende de Termux ou libusb externo.
 */
class AndroidBulkOnlyTransport private constructor(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val bulkIn: UsbEndpoint,
    private val bulkOut: UsbEndpoint,
) : Closeable {
    private val nextTag = AtomicInteger(1)

    fun executeIn(
        command: ByteArray,
        commandLength: Int,
        expectedLength: Int,
        lun: Int = 0,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        allowShortPacket: Boolean = false,
    ): ByteArray {
        require(expectedLength >= 0)
        val tag = nextTag.getAndIncrement()
        val cbw = BulkOnlyProtocol.buildCommandBlockWrapper(
            tag = tag,
            transferLength = expectedLength,
            directionIn = true,
            lun = lun,
            command = command,
            commandLength = commandLength,
        )
        writeExact(cbw, timeoutMs)

        val payload = when {
            expectedLength == 0 -> ByteArray(0)
            allowShortPacket -> readAtMost(expectedLength, timeoutMs)
            else -> ByteArray(expectedLength).also { readExact(it, timeoutMs) }
        }
        readAndValidateStatus(tag, timeoutMs)
        return payload
    }

    fun executeOut(
        command: ByteArray,
        commandLength: Int,
        payload: ByteArray = ByteArray(0),
        lun: Int = 0,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    ) {
        val tag = nextTag.getAndIncrement()
        val cbw = BulkOnlyProtocol.buildCommandBlockWrapper(
            tag = tag,
            transferLength = payload.size,
            directionIn = false,
            lun = lun,
            command = command,
            commandLength = commandLength,
        )
        writeExact(cbw, timeoutMs)
        if (payload.isNotEmpty()) writeExact(payload, timeoutMs)
        readAndValidateStatus(tag, timeoutMs)
    }

    private fun readAndValidateStatus(tag: Int, timeoutMs: Int) {
        val bytes = ByteArray(BulkOnlyProtocol.CSW_SIZE)
        readExact(bytes, timeoutMs)
        val status = try {
            BulkOnlyProtocol.parseCommandStatusWrapper(bytes, tag)
        } catch (error: IllegalArgumentException) {
            throw IOException(error.message, error)
        }
        if (!status.success) {
            throw IOException(
                "O dispositivo rejeitou o comando USB: status=${status.status}, residue=${status.residue}",
            )
        }
    }

    private fun writeExact(bytes: ByteArray, timeoutMs: Int) {
        var offset = 0
        while (offset < bytes.size) {
            val requested = minOf(bytes.size - offset, MAX_TRANSFER_CHUNK)
            val written = connection.bulkTransfer(bulkOut, bytes, offset, requested, timeoutMs)
            if (written <= 0) {
                throw IOException("Falha/timeout ao enviar USB: offset=$offset tamanho=${bytes.size}")
            }
            offset += written
        }
    }

    private fun readExact(bytes: ByteArray, timeoutMs: Int) {
        var offset = 0
        while (offset < bytes.size) {
            val requested = minOf(bytes.size - offset, MAX_TRANSFER_CHUNK)
            val read = connection.bulkTransfer(bulkIn, bytes, offset, requested, timeoutMs)
            if (read <= 0) {
                throw IOException("Falha/timeout ao receber USB: offset=$offset tamanho=${bytes.size}")
            }
            offset += read
        }
    }

    private fun readAtMost(maximumLength: Int, timeoutMs: Int): ByteArray {
        val buffer = ByteArray(maximumLength)
        val read = connection.bulkTransfer(bulkIn, buffer, 0, maximumLength, timeoutMs)
        if (read <= 0) throw IOException("Falha/timeout ao receber dados USB")
        return buffer.copyOf(read)
    }

    override fun close() {
        runCatching { connection.releaseInterface(usbInterface) }
        connection.close()
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 15_000
        private const val MAX_TRANSFER_CHUNK = 1024 * 1024

        fun open(
            usbManager: UsbManager,
            device: UsbDevice,
            interfaceMatcher: (UsbInterface) -> Boolean,
        ): AndroidBulkOnlyTransport {
            require(usbManager.hasPermission(device)) {
                "Permissão USB não concedida para ${device.deviceName}"
            }
            val candidate = (0 until device.interfaceCount)
                .map(device::getInterface)
                .firstOrNull(interfaceMatcher)
                ?: throw IOException("Interface USB compatível não encontrada")

            var bulkIn: UsbEndpoint? = null
            var bulkOut: UsbEndpoint? = null
            for (index in 0 until candidate.endpointCount) {
                val endpoint = candidate.getEndpoint(index)
                if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                when (endpoint.direction) {
                    UsbConstants.USB_DIR_IN -> if (bulkIn == null) bulkIn = endpoint
                    UsbConstants.USB_DIR_OUT -> if (bulkOut == null) bulkOut = endpoint
                }
            }
            val input = bulkIn ?: throw IOException("Endpoint Bulk IN não encontrado")
            val output = bulkOut ?: throw IOException("Endpoint Bulk OUT não encontrado")
            val connection = usbManager.openDevice(device)
                ?: throw IOException("O Android não conseguiu abrir o dispositivo USB")
            if (!connection.claimInterface(candidate, true)) {
                connection.close()
                throw IOException("Não foi possível assumir a interface USB")
            }
            return AndroidBulkOnlyTransport(connection, candidate, input, output)
        }
    }
}
