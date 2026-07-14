package com.tayson.rockflash.media

import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.tayson.rockflash.ui.RockFlashingToolTheme
import java.io.FileInputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UniversalMediaWriterActivity : ComponentActivity() {
    private var selectedImageUri: Uri? = null

    private val imagePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) inspectImage(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedImageUri = savedInstanceState?.getString(STATE_IMAGE_URI)?.let(Uri::parse)
        UsbMediaWriterService.scan(this)

        setContent {
            RockFlashingToolTheme {
                UniversalMediaWriterRoute(
                    onBack = ::finish,
                    onScan = { UsbMediaWriterService.scan(this) },
                    onSelectImage = {
                        imagePicker.launch(
                            arrayOf(
                                "application/octet-stream",
                                "application/x-iso9660-image",
                                "application/x-raw-disk-image",
                                "*/*",
                            ),
                        )
                    },
                    onSelectTarget = MediaWriterStore::selectTarget,
                    onRequestPermission = { deviceId -> UsbMediaWriterService.requestPermission(this, deviceId) },
                    onWrite = ::startWrite,
                    onClearLogs = MediaWriterStore::clearLogs,
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        selectedImageUri?.let { outState.putString(STATE_IMAGE_URI, it.toString()) }
        super.onSaveInstanceState(outState)
    }

    private fun inspectImage(uri: Uri) {
        lifecycleScope.launch {
            try {
                runCatching {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val metadata = queryMetadata(uri)
                val inspection = withContext(Dispatchers.IO) {
                    val header = contentResolver.openInputStream(uri)?.use { input ->
                        val buffer = ByteArray(HEADER_BYTES)
                        val count = input.read(buffer)
                        if (count <= 0) ByteArray(0) else buffer.copyOf(count)
                    } ?: throw IOException("Não foi possível abrir a imagem")
                    val trailer = readTrailer(uri, metadata.sizeBytes)
                    DiskImageInspector.inspect(
                        fileName = metadata.displayName,
                        sizeBytes = metadata.sizeBytes,
                        header = header,
                        trailer = trailer,
                    )
                }
                selectedImageUri = uri
                MediaWriterStore.setImage(metadata.displayName, metadata.sizeBytes, inspection)
                MediaWriterStore.appendLog("Imagem selecionada: ${metadata.displayName}")
                MediaWriterStore.appendLog(inspection.description)
            } catch (error: Exception) {
                selectedImageUri = null
                MediaWriterStore.setImage(null, null, null)
                MediaWriterStore.appendLog(
                    "Falha ao inspecionar imagem: ${error.message ?: error.javaClass.simpleName}",
                )
            }
        }
    }

    private fun startWrite() {
        val state = MediaWriterStore.state.value
        val uri = selectedImageUri
        val target = state.selectedTarget
        val size = state.imageSizeBytes
        when {
            uri == null -> MediaWriterStore.appendLog("Selecione novamente a imagem")
            target == null -> MediaWriterStore.appendLog("Selecione uma unidade USB de destino")
            size == null || size <= 0L -> MediaWriterStore.appendLog("Tamanho da imagem inválido")
            !state.canWrite -> MediaWriterStore.appendLog("A gravação está bloqueada no estado atual")
            else -> UsbMediaWriterService.write(this, target.deviceId, uri, size)
        }
    }

    private suspend fun queryMetadata(uri: Uri): ImageMetadata = withContext(Dispatchers.IO) {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor: Cursor? = contentResolver.query(uri, projection, null, null, null)
        var name: String? = null
        var size: Long? = null
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0 && !it.isNull(nameIndex)) name = it.getString(nameIndex)
                if (sizeIndex >= 0 && !it.isNull(sizeIndex)) size = it.getLong(sizeIndex)
            }
        }
        if (size == null || size!! <= 0L) {
            size = contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                descriptor.length.takeIf { it > 0L }
            }
        }
        val resolvedSize = size ?: throw IOException("O provedor não informou o tamanho da imagem")
        ImageMetadata(name ?: uri.lastPathSegment ?: "imagem.img", resolvedSize)
    }

    private fun readTrailer(uri: Uri, sizeBytes: Long): ByteArray {
        if (sizeBytes <= 0L) return ByteArray(0)
        return runCatching {
            contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).use { stream ->
                    val length = minOf(TRAILER_BYTES.toLong(), sizeBytes).toInt()
                    stream.channel.position(sizeBytes - length)
                    val buffer = ByteArray(length)
                    var offset = 0
                    while (offset < length) {
                        val count = stream.read(buffer, offset, length - offset)
                        if (count < 0) break
                        if (count == 0) continue
                        offset += count
                    }
                    buffer.copyOf(offset)
                }
            } ?: ByteArray(0)
        }.getOrElse { ByteArray(0) }
    }

    private data class ImageMetadata(val displayName: String, val sizeBytes: Long)

    companion object {
        private const val STATE_IMAGE_URI = "media_writer_image_uri"
        private const val HEADER_BYTES = 64 * 1024
        private const val TRAILER_BYTES = 512
    }
}
