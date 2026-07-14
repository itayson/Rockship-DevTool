package com.tayson.rockflash.usb

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

data class UsbHostDeviceInfo(
    val deviceName: String,
    val deviceId: Int,
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

    fun massStorageDevices(): List<UsbHostDeviceInfo> = massStorageUsbDevices().map(::toInfo)

    fun massStorageUsbDevices(): List<UsbDevice> = usbManager.deviceList.values.filter(::isMassStorage)

    fun findDevice(deviceName: String): UsbDevice? = usbManager.deviceList[deviceName]

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    private fun isMassStorage(device: UsbDevice): Boolean =
        (0 until device.interfaceCount).any { index ->
            val usbInterface = device.getInterface(index)
            usbInterface.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE &&
                usbInterface.interfaceSubclass == 0x06 &&
                usbInterface.interfaceProtocol == 0x50
        }

    private fun toInfo(device: UsbDevice): UsbHostDeviceInfo = UsbHostDeviceInfo(
        deviceName = device.deviceName,
        deviceId = device.deviceId,
        vendorId = device.vendorId,
        productId = device.productId,
        productName = runCatching { device.productName }.getOrNull(),
        manufacturerName = runCatching { device.manufacturerName }.getOrNull(),
        massStorage = isMassStorage(device),
    )
}
