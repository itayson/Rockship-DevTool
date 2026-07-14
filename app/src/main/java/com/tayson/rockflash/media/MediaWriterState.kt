package com.tayson.rockflash.media

import com.tayson.rockflash.core.LogLine
import com.tayson.rockflash.core.OperationProgress
import com.tayson.rockflash.core.TransferPhase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class UsbMediaTargetState(
    val deviceId: Int,
    val displayName: String,
    val vidPid: String,
    val transport: UsbStorageTransport,
    val supportLevel: MediaSupportLevel,
    val reason: String,
    val permissionGranted: Boolean,
    val vendor: String? = null,
    val product: String? = null,
    val blockSize: Int? = null,
    val capacityBytes: ULong? = null,
    val initializationError: String? = null,
) {
    val ready: Boolean
        get() = supportLevel == MediaSupportLevel.SUPPORTED &&
            permissionGranted && capacityBytes != null && initializationError == null

    val modelLabel: String
        get() = listOfNotNull(vendor?.takeIf(String::isNotBlank), product?.takeIf(String::isNotBlank))
            .joinToString(" ")
            .ifBlank { displayName }
}

data class MediaWriterState(
    val targets: List<UsbMediaTargetState> = emptyList(),
    val selectedDeviceId: Int? = null,
    val imageName: String? = null,
    val imageSizeBytes: Long? = null,
    val imageInspection: DiskImageInspection? = null,
    val operation: OperationProgress? = null,
    val logs: List<LogLine> = emptyList(),
) {
    val selectedTarget: UsbMediaTargetState?
        get() = targets.firstOrNull { it.deviceId == selectedDeviceId }

    val canWrite: Boolean
        get() {
            val target = selectedTarget ?: return false
            val size = imageSizeBytes ?: return false
            val capacity = target.capacityBytes ?: return false
            return target.ready &&
                imageInspection?.canRawWrite == true &&
                size > 0L && size.toULong() <= capacity &&
                operation?.running != true
        }
}

object MediaWriterStore {
    private const val MAX_LOG_LINES = 400
    private val mutableState = MutableStateFlow(MediaWriterState())
    val state: StateFlow<MediaWriterState> = mutableState.asStateFlow()

    fun setTargets(targets: List<UsbMediaTargetState>) {
        val snapshot = targets.toList()
        mutableState.update { current ->
            val selected = current.selectedDeviceId?.takeIf { id -> snapshot.any { it.deviceId == id } }
            current.copy(targets = snapshot, selectedDeviceId = selected)
        }
    }

    fun selectTarget(deviceId: Int?) {
        mutableState.update { current ->
            current.copy(selectedDeviceId = deviceId?.takeIf { id -> current.targets.any { it.deviceId == id } })
        }
    }

    fun setImage(name: String?, sizeBytes: Long?, inspection: DiskImageInspection?) {
        mutableState.update {
            it.copy(
                imageName = name,
                imageSizeBytes = sizeBytes,
                imageInspection = inspection,
                operation = null,
            )
        }
    }

    fun startOperation(totalBytes: Long, detail: String) {
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

    fun updateOperation(phase: TransferPhase, completed: Long, total: Long, detail: String) {
        mutableState.update {
            it.copy(
                operation = OperationProgress(
                    phase = phase,
                    completedBytes = completed.coerceIn(0L, total.coerceAtLeast(0L)),
                    totalBytes = total,
                    detail = detail,
                ),
            )
        }
    }

    fun finishOperation(total: Long, detail: String) {
        updateOperation(TransferPhase.COMPLETED, total, total, detail)
    }

    fun failOperation(total: Long, detail: String) {
        mutableState.update { current ->
            current.copy(
                operation = OperationProgress(
                    phase = TransferPhase.FAILED,
                    completedBytes = current.operation?.completedBytes?.coerceAtMost(total) ?: 0L,
                    totalBytes = total,
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
