package com.tayson.rockflash.usb

import android.hardware.usb.UsbDevice

data class RockchipUsbDevice(
    val device: UsbDevice,
    val vendorId: Int,
    val productId: Int,
    val deviceName: String,
    val mode: RockchipMode,
) {
    val vidPid: String
        get() = "%04x:%04x".format(vendorId, productId)
}

enum class RockchipMode {
    LOADER,
    MASKROM,
    UNKNOWN,
}
