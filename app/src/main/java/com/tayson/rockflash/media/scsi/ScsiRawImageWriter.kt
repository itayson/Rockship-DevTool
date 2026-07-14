package com.tayson.rockflash.media.scsi

import java.io.EOFException
import java.io.InputStream

enum class MediaWritePhase {
    WRITING,
    VERIFYING,
}

data class MediaWriteProgress(
    val phase: MediaWritePhase,
    val completedBytes: Long,
    val totalBytes: Long,
)

/** Grava e verifica uma imagem RAW em uma unidade SCSI USB. */
class ScsiRawImageWriter(
    private val device: ScsiBlockDevice,
) {
    fun writeAndVerify(
        sourceFactory: () -> InputStream,
        imageSizeBytes: Long,
        onProgress: (MediaWriteProgress) -> Unit = {},
    ) {
        require(imageSizeBytes > 0L) { "A imagem está vazia" }
        require(imageSizeBytes.toULong() <= device.capacity.totalBytes) {
            "Imagem de $imageSizeBytes bytes excede a mídia de ${device.capacity.totalBytes} bytes"
        }

        write(sourceFactory, imageSizeBytes, onProgress)
        verify(sourceFactory, imageSizeBytes, onProgress)
    }

    private fun write(
        sourceFactory: () -> InputStream,
        imageSizeBytes: Long,
        onProgress: (MediaWriteProgress) -> Unit,
    ) {
        sourceFactory().buffered().use { source ->
            transferImage(source, imageSizeBytes) { lba, payload, completed ->
                device.writeBlocks(lba, payload)
                onProgress(MediaWriteProgress(MediaWritePhase.WRITING, completed, imageSizeBytes))
            }
        }
    }

    private fun verify(
        sourceFactory: () -> InputStream,
        imageSizeBytes: Long,
        onProgress: (MediaWriteProgress) -> Unit,
    ) {
        sourceFactory().buffered().use { source ->
            transferImage(source, imageSizeBytes) { lba, expected, completed ->
                val actual = device.readBlocks(lba, expected.size / device.capacity.blockSize)
                if (!actual.contentEquals(expected)) {
                    val mismatch = expected.indices.firstOrNull { expected[it] != actual[it] } ?: 0
                    val absoluteOffset = lba * device.capacity.blockSize.toULong() + mismatch.toULong()
                    throw IllegalStateException("Verificação falhou no byte $absoluteOffset")
                }
                onProgress(MediaWriteProgress(MediaWritePhase.VERIFYING, completed, imageSizeBytes))
            }
        }
    }

    private inline fun transferImage(
        source: InputStream,
        imageSizeBytes: Long,
        consume: (lba: ULong, payload: ByteArray, completedBytes: Long) -> Unit,
    ) {
        val blockSize = device.capacity.blockSize
        val maxBlocks = minOf(MAX_BLOCKS_PER_COMMAND, MAX_CHUNK_BYTES / blockSize).coerceAtLeast(1)
        var lba = 0uL
        var completed = 0L

        while (completed < imageSizeBytes) {
            val remaining = imageSizeBytes - completed
            val requestedDataBytes = minOf(remaining, maxBlocks.toLong() * blockSize).toInt()
            val blocks = ((requestedDataBytes - 1) / blockSize) + 1
            val payload = ByteArray(blocks * blockSize)
            source.readExactly(payload, requestedDataBytes)
            completed += requestedDataBytes
            consume(lba, payload, completed)
            lba += blocks.toULong()
        }
    }

    private fun InputStream.readExactly(target: ByteArray, length: Int) {
        var offset = 0
        while (offset < length) {
            val count = read(target, offset, length - offset)
            if (count < 0) throw EOFException("A imagem terminou antes do tamanho informado")
            if (count == 0) continue
            offset += count
        }
    }

    companion object {
        private const val MAX_CHUNK_BYTES = 8 * 1024 * 1024
        private const val MAX_BLOCKS_PER_COMMAND = 0xFFFF
    }
}
