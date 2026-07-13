package com.tayson.rockflash.report

import com.tayson.rockflash.safety.SafetySnapshot
import com.tayson.rockflash.usb.RockchipUsbDevice
import java.time.Instant
import org.json.JSONObject

object DeviceReport {
    fun create(
        device: RockchipUsbDevice?,
        safety: SafetySnapshot,
        backendVersion: String,
        log: String,
    ): String = JSONObject().apply {
        put("generatedAt", Instant.now().toString())
        put("backendVersion", backendVersion)
        put("batteryPercent", safety.batteryPercent)
        put("charging", safety.charging)
        put("powerSaveMode", safety.powerSaveMode)
        put("device", device?.let {
            JSONObject().apply {
                put("vid", "0x%04x".format(it.vendorId))
                put("pid", "0x%04x".format(it.productId))
                put("vidPid", it.vidPid)
                put("deviceName", it.deviceName)
                put("mode", it.mode.name)
                put("interfaces", it.device.interfaceCount)
            }
        } ?: JSONObject.NULL)
        put("log", log)
    }.toString(2)
}
