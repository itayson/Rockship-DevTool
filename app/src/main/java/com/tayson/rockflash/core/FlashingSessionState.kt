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

enum class FirmwareKind(val displayName: String) {
    NONE("Nenhum firmware"),
    RAW_DISK_IMAGE("Imagem bruta de disco"),
    ROCKCHIP_CONTAINER("Firmware Rockchip RKFW/RKAF"),
    PARAMETER("Tabela parameter.txt"),
    UNSUPPORTED("Arquivo não suportado"),
}

enum class TransferPhase(val displayName: String) {
    PREPARING("Preparando"),
    WRITING("Gravando"),
    VERIFYING("Verificando"),
    COMPLETED("Concluído"),
    FAILED("Falhou"),
}

data class OperationProgress(
    val phase: TransferPhase,
    val completedBytes: Long,
    val totalBytes: Long,
    val detail: String? = null,
) {
    val fraction: Float
        get() = when {
            totalBytes <= 0L -> 0f
            completedBytes >= totalBytes -> 1f
            else -> (completedBytes.toDouble() / totalBytes.toDouble()).toFloat()
        }

    val running: Boolean
        get() = phase == TransferPhase.PREPARING ||
            phase == TransferPhase.WRITING ||
            phase == TransferPhase.VERIFYING
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

    fun sizeLabel(): String = sizeBytes?.let(::formatByteCount) ?: "restante"

    companion object {
        private const val SECTOR_SIZE_BYTES = 512L
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
    val firmwareSizeBytes: Long? = null,
    val firmwareKind: FirmwareKind = FirmwareKind.NONE,
    val partitions: List<PartitionEntry> = emptyList(),
    val operation: OperationProgress? = null,
    val logs: List<LogLine> = emptyList(),
) {
    val canFlashRawImage: Boolean
        get() = connectionMode == ConnectionMode.LOADER &&
            firmwareKind == FirmwareKind.RAW_DISK_IMAGE &&
            firmwareSizeBytes != null &&
            operation?.running != true
}

/**
 * Estado compartilhado entre a Activity Compose e os serviços USB/root.
 * Mantém somente metadados; URIs, descritores USB e buffers não são persistidos aqui.
 */
object FlashingSessionStore {
    private const val MAX_LOG_LINES = 500

    private val mutableState = MutableStateFlow(FlashingSessionState())
    val state: StateFlow<FlashingSessionState> = mutableState.asStateFlow()

    fun setConnection(mode: ConnectionMode, device: String? = null) {
        mutableState.update { it.copy(connectionMode = mode, connectedDevice = device) }
    }

    fun setFirmware(name: String?, sizeBytes: Long?, kind: FirmwareKind) {
        mutableState.update {
            it.copy(
                firmwareName = name,
                firmwareSizeBytes = sizeBytes,
                firmwareKind = kind,
                operation = null,
            )
        }
    }

    fun setPartitions(partitions: List<PartitionEntry>) {
        mutableState.update { it.copy(partitions = partitions.toList()) }
    }

    fun startOperation(totalBytes: Long, detail: String? = null) {
        mutableState.update {
            it.copy(
                operation = OperationProgress(
                    phase = TransferPhase.PREPARING,
                    completedBytes = 0L,
                    totalBytes = totalBytes,
                    detail = detail,
                ),
            )
        }
    }

    fun updateOperation(
        phase: TransferPhase,
        completedBytes: Long,
        totalBytes: Long,
        detail: String? = null,
    ) {
        mutableState.update {
            it.copy(
                operation = OperationProgress(
                    phase = phase,
                    completedBytes = completedBytes.coerceIn(0L, totalBytes.coerceAtLeast(0L)),
                    totalBytes = totalBytes,
                    detail = detail,
                ),
            )
        }
    }

    fun finishOperation(totalBytes: Long, detail: String? = null) {
        updateOperation(TransferPhase.COMPLETED, totalBytes, totalBytes, detail)
    }

    fun failOperation(totalBytes: Long, detail: String) {
        mutableState.update { current ->
            val completed = current.operation?.completedBytes?.coerceAtMost(totalBytes) ?: 0L
            current.copy(
                operation = OperationProgress(
                    phase = TransferPhase.FAILED,
                    completedBytes = completed,
                    totalBytes = totalBytes,
                    detail = detail,
                ),
            )
        }
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

fun formatByteCount(bytes: Long): String {
    val mib = bytes.toDouble() / (1024.0 * 1024.0)
    return when {
        mib >= 1024.0 -> "%.2f GiB".format(Locale.US, mib / 1024.0)
        mib >= 1.0 -> "%.2f MiB".format(Locale.US, mib)
        else -> "%.2f KiB".format(Locale.US, bytes / 1024.0)
    }
}
