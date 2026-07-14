package com.tayson.rockflash.usb

import android.hardware.usb.UsbDeviceConnection

/**
 * Detecta o estágio RockUSB pelo descritor USB bruto.
 *
 * O rkdeveloptool oficial classifica MaskROM/Loader pelo bit 0 de bcdUSB:
 * valor par = MaskROM; valor ímpar = Loader. Android não expõe bcdUSB por uma
 * API tipada, portanto os bytes 2 e 3 do device descriptor são lidos em LE.
 */
object RockchipModeDetector {
    fun detect(connection: UsbDeviceConnection): RockchipMode {
        val descriptor = connection.rawDescriptors ?: return RockchipMode.UNKNOWN
        if (descriptor.size < USB_DEVICE_DESCRIPTOR_MIN_SIZE) return RockchipMode.UNKNOWN

        val bcdUsb = (descriptor[BCD_USB_LOW].toInt() and 0xFF) or
            ((descriptor[BCD_USB_HIGH].toInt() and 0xFF) shl 8)
        return fromBcdUsb(bcdUsb)
    }

    internal fun fromBcdUsb(bcdUsb: Int): RockchipMode =
        if (bcdUsb and 0x01 == 0) RockchipMode.MASKROM else RockchipMode.LOADER

    private const val USB_DEVICE_DESCRIPTOR_MIN_SIZE = 4
    private const val BCD_USB_LOW = 2
    private const val BCD_USB_HIGH = 3
}
