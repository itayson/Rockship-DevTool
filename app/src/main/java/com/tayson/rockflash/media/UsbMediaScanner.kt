package com.tayson.rockflash.media

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.tayson.rockflash.media.scsi.AndroidScsiBlockDevice
import com.tayson.rockflash.media.scsi.ScsiProtocol

enum class MediaSupportLevel {
    SUPPORTED,
    EXPERIMENTAL,
    REQUIRES_ROOT,
    REQUIRES_CONVERSION,
    UNSUPPORTED_HARDWARE,
}

enum class UsbStorageTransport {
    SCSI_BOT,
    UAS,
    UFI,
    ATAPI,
    CBI,
    UNKNOWN,
}

data class UsbMediaCandidate(
    val device: UsbDevice,
    val interfaceId: Int,
    val transport: UsbStorageTransport,
    val supportLevel: MediaSupportLevel,
    val reason: String,
) {
    val vidPid: String
        get() = "%04x:%04x".format(device.vendorId, device.productId)

    val displayName: String
        get() = device.productName ?: device.deviceName
}

/**
 * Enumera dispositivos de armazenamento expostos pelo UsbManager, inclusive os
 * conectados atrás de hubs. O hub não é o destino; cada dispositivo downstream
 * aparece separadamente para o Android quando a controladora e a alimentação
 * permitem.
 */
object UsbMediaScanner {
    fun scan(usbManager: UsbManager): List<UsbMediaCandidate> =
        usbManager.deviceList.values.flatMap(::classifyDevice)

    fun classifyDevice(device: UsbDevice): List<UsbMediaCandidate> =
        (0 until device.interfaceCount)
            .map(device::getInterface)
            .filter { it.interfaceClass == ScsiProtocol.USB_CLASS_MASS_STORAGE }
            .map { usbInterface -> classifyInterface(device, usbInterface) }

    private fun classifyInterface(device: UsbDevice, usbInterface: UsbInterface): UsbMediaCandidate {
        val transport = when {
            usbInterface.interfaceProtocol == ScsiProtocol.PROTOCOL_UAS -> UsbStorageTransport.UAS
            usbInterface.interfaceSubclass == ScsiProtocol.SUBCLASS_UFI -> UsbStorageTransport.UFI
            usbInterface.interfaceSubclass == ScsiProtocol.SUBCLASS_ATAPI -> UsbStorageTransport.ATAPI
            usbInterface.interfaceProtocol == ScsiProtocol.PROTOCOL_CBI ||
                usbInterface.interfaceProtocol == ScsiProtocol.PROTOCOL_CBI_NO_INTERRUPT -> UsbStorageTransport.CBI
            AndroidScsiBlockDevice.isSupportedBotInterface(usbInterface) -> UsbStorageTransport.SCSI_BOT
            else -> UsbStorageTransport.UNKNOWN
        }

        val (support, reason) = when (transport) {
            UsbStorageTransport.SCSI_BOT -> MediaSupportLevel.SUPPORTED to
                "SCSI Transparent com Bulk-Only; compatível com gravação bruta e verificação"
            UsbStorageTransport.UAS -> MediaSupportLevel.EXPERIMENTAL to
                "UAS exige uma fila de comandos e transporte diferente do Bulk-Only"
            UsbStorageTransport.UFI -> MediaSupportLevel.EXPERIMENTAL to
                "Unidade de disquete USB/UFI requer comandos e geometria específicos"
            UsbStorageTransport.ATAPI -> MediaSupportLevel.EXPERIMENTAL to
                "Unidade óptica ATAPI exige MMC, criação de sessão e finalização de mídia"
            UsbStorageTransport.CBI -> MediaSupportLevel.EXPERIMENTAL to
                "CBI não usa o transporte Bulk-Only implementado"
            UsbStorageTransport.UNKNOWN -> MediaSupportLevel.UNSUPPORTED_HARDWARE to
                "Classe Mass Storage detectada, mas combinação subclass/protocol desconhecida"
        }

        return UsbMediaCandidate(
            device = device,
            interfaceId = usbInterface.id,
            transport = transport,
            supportLevel = support,
            reason = reason,
        )
    }
}
