package com.tayson.rockflash.usb

import java.io.InputStream
import java.io.OutputStream

data class TransferProgress(
    val completedBytes: Long,
    val totalBytes: Long?,
)

/**
 * Contrato independente da UI para as operações equivalentes ao rkdeveloptool.
 * Implementações podem usar UsbDeviceConnection diretamente ou JNI/libusb.
 */
interface RockUsbOperations {
    suspend fun downloadBootloader(
        miniLoader: InputStream,
        onProgress: (TransferProgress) -> Unit = {},
    )

    suspend fun readLba(
        startSector: Long,
        sectorCount: Long,
        destination: OutputStream,
        onProgress: (TransferProgress) -> Unit = {},
    )

    suspend fun writeLba(
        startSector: Long,
        sectorCount: Long,
        source: InputStream,
        verifyReadback: Boolean = true,
        onProgress: (TransferProgress) -> Unit = {},
    )

    suspend fun eraseFlash(onProgress: (TransferProgress) -> Unit = {})

    suspend fun readParameter(): ByteArray

    suspend fun readGpt(): ByteArray

    suspend fun resetDevice()
}
