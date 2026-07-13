package com.tayson.rockflash.usb

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager

class RockchipUsbController(context: Context) {
    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager

    fun findDevices(): List<RockchipUsbDevice> =
        usbManager.deviceList.values
            .asSequence()
            .filter { it.vendorId == ROCKCHIP_VENDOR_ID }
            .map { it.toRockchipDevice() }
            .sortedWith(compareBy({ it.productId }, { it.deviceName }))
            .toList()

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    fun requestPermission(device: UsbDevice, action: String) {
        val intent = Intent(action).setPackage(appContext.packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        usbManager.requestPermission(device, PendingIntent.getBroadcast(appContext, 2207, intent, flags))
    }

    fun open(device: UsbDevice): UsbDeviceConnection? = usbManager.openDevice(device)

    fun describeInterfaces(device: UsbDevice): String = buildString {
        for (interfaceIndex in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(interfaceIndex)
            appendLine(
                "Interface $interfaceIndex: class=${usbInterface.interfaceClass} " +
                    "subclass=${usbInterface.interfaceSubclass} protocol=${usbInterface.interfaceProtocol}",
            )
            for (endpointIndex in 0 until usbInterface.endpointCount) {
                val endpoint = usbInterface.getEndpoint(endpointIndex)
                appendLine(
                    "  Endpoint $endpointIndex: address=0x${endpoint.address.toString(16)} " +
                        "type=${endpoint.type} direction=${endpoint.direction} maxPacket=${endpoint.maxPacketSize}",
                )
            }
        }
    }.trim()

    private fun UsbDevice.toRockchipDevice(): RockchipUsbDevice {
        val inferredMode = when (productId) {
            0x320B -> RockchipMode.LOADER
            else -> RockchipMode.UNKNOWN
        }
        return RockchipUsbDevice(
            device = this,
            vendorId = vendorId,
            productId = productId,
            deviceName = deviceName,
            mode = inferredMode,
        )
    }

    companion object {
        const val ROCKCHIP_VENDOR_ID = 0x2207
    }
}
