package com.tayson.rockflash.safety

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager

data class SafetySnapshot(
    val batteryPercent: Int,
    val charging: Boolean,
    val powerSaveMode: Boolean,
) {
    val writeReady: Boolean
        get() = batteryPercent >= 60 && charging && !powerSaveMode

    fun summary(): String = buildString {
        append("Bateria: $batteryPercent%")
        append(if (charging) " • carregando" else " • sem carregador")
        append(if (powerSaveMode) " • economia ativa" else " • economia desativada")
        append(if (writeReady) " • escrita tecnicamente liberável" else " • escrita bloqueada")
    }
}

object SafetyGate {
    fun snapshot(context: Context): SafetySnapshot {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val percent = if (level >= 0 && scale > 0) (level * 100 / scale) else 0
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return SafetySnapshot(
            batteryPercent = percent,
            charging = charging,
            powerSaveMode = powerManager.isPowerSaveMode,
        )
    }
}
