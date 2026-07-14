package com.tayson.rockflash.core

import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ConnectionMode(val displayName: String) {
    NONE("Nenhum dispositivo"),
    MASKROM("Modo MaskROM detectado"),
    LOADER("Modo Loader detectado"),
    LOCAL_ROOT("Modo Local Root"),
    ROCKCHIP_UNKNOWN("Dispositivo Rockchip detectado"),
}

data class PartitionEntry(
    val name: String,
    val startLba: Long,
    val sectorCount: Long?,
    val flags: String = "",
) {
    val sizeBytes: Long?
        get() = sectorCount?.let { Math.multiplyExact(it, SECTOR_SIZE_BYTES) }

    val endLbaExclusive: Long?
        get() = sectorCount?.let { Math.addExact(startLba, it) }

    fun startHex(): String = "0x%08x".format(Locale.US, startLba)

    fun sizeLabel(): String = sizeBytes?.let(::formatBytes) ?: "restante"

    companion object {
        private const val SECTOR_SIZE_BYTES = 512L

        private fun formatBytes(bytes: Long): String {
            val mib = bytes.toDouble() / (1024.0 * 1024.0)
            return if (mib >= 1024.0) {
                "%.2f GiB".format(Locale.US, mib / 1024.0)
            } else {
                "%.2f MiB".format(Locale.US, mib)
            }
        }
    }
}

data class LogLine(
    val timestampMillis: Long = System.currentTimeMillis(),
    val message: String,
)

data class FlashingSessionState(
    val connectionMode: ConnectionMode = ConnectionMode.NONE,
    val connectedDevice: String? = null,
    val firmwareName: String? = null,
    val partitions: List<PartitionEntry> = emptyList(),
    val logs: List<LogLine> = emptyList(),
)

/**
 * Estado compartilhado entre a Activity Compose e os serviços USB/root.
 * Mantém somente metadados; arquivos e descritores USB nunca são armazenados aqui.
 */
object FlashingSessionStore {
    private const val MAX_LOG_LINES = 500

    private val mutableState = MutableStateFlow(FlashingSessionState())
    val state: StateFlow<FlashingSessionState> = mutableState.asStateFlow()

    fun setConnection(mode: ConnectionMode, device: String? = null) {
        mutableState.update { it.copy(connectionMode = mode, connectedDevice = device) }
    }

    fun setFirmware(name: String?) {
        mutableState.update { it.copy(firmwareName = name) }
    }

    fun setPartitions(partitions: List<PartitionEntry>) {
        mutableState.update { it.copy(partitions = partitions) }
    }

    fun appendLog(message: String) {
        val normalized = message.trim()
        if (normalized.isEmpty()) return
        mutableState.update { current ->
            current.copy(logs = (current.logs + LogLine(message = normalized)).takeLast(MAX_LOG_LINES))
        }
    }

    fun clearLogs() {
        mutableState.update { it.copy(logs = emptyList()) }
    }
}
