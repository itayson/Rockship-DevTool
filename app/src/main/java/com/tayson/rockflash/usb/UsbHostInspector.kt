package com.tayson.rockflash.usb

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

data class UsbHostDeviceInfo(
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
    val productName: String?,
    val manufacturerName: String?,
    val massStorage: Boolean,
) {
    val vidPid: String
        get() = "%04x:%04x".format(vendorId, productId)

    val label: String
        get() = buildString {
            append(vidPid)
            productName?.takeIf { it.isNotBlank() }?.let { append(" • ").append(it) }
            manufacturerName?.takeIf { it.isNotBlank() }?.let { append(" • ").append(it) }
            append(" • ").append(deviceName)
        }
}

class UsbHostInspector(context: Context) {
    private val usbManager = context.applicationContext.getSystemService(Context.USB_SERVICE) as UsbManager

    fun allDevices(): List<UsbHostDeviceInfo> = usbManager.deviceList.values.map(::toInfo)

    fun massStorageDevices(): List<UsbHostDeviceInfo> = allDevices().filter { it.massStorage }

    private fun toInfo(device: UsbDevice): UsbHostDeviceInfo {
        val massStorage = (0 until device.interfaceCount).any { index ->
            device.getInterface(index).interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE
        }
        return UsbHostDeviceInfo(
            deviceName = device.deviceName,
            vendorId = device.vendorId,
            productId = device.productId,
            productName = runCatching { device.productName }.getOrNull(),
            manufacturerName = runCatching { device.manufacturerName }.getOrNull(),
            massStorage = massStorage,
        )
    }
}
