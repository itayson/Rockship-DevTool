package com.tayson.rockflash.ui

import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.tayson.rockflash.core.FirmwareKind
import com.tayson.rockflash.core.FlashingSessionStore
import com.tayson.rockflash.core.formatByteCount
import com.tayson.rockflash.firmware.FirmwareInspector
import com.tayson.rockflash.firmware.ParameterParseException
import com.tayson.rockflash.firmware.ParameterParser
import com.tayson.rockflash.usb.UsbHostForegroundService
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RockFlashingToolActivity : ComponentActivity() {
    private var selectedFirmwareUri: Uri? = null

    private val firmwarePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) handleFirmwareSelection(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedFirmwareUri = savedInstanceState
            ?.getString(STATE_FIRMWARE_URI)
            ?.let(Uri::parse)
        UsbHostForegroundService.start(this)

        setContent {
            RockFlashingToolTheme {
                RockFlashingToolRoute(
                    onSelectFirmware = {
                        firmwarePicker.launch(
                            arrayOf(
                                "application/octet-stream",
                                "application/x-img",
                                "text/plain",
                                "*/*",
                            ),
                        )
                    },
                    onScan = {
                        FlashingSessionStore.appendLog("Varredura manual e sondagem root solicitadas")
                        UsbHostForegroundService.probeConnections(this)
                    },
                    onFlashRawImage = ::startRawImageFlash,
                    onClearLogs = FlashingSessionStore::clearLogs,
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        selectedFirmwareUri?.let { outState.putString(STATE_FIRMWARE_URI, it.toString()) }
        super.onSaveInstanceState(outState)
    }

    private fun handleFirmwareSelection(uri: Uri) {
        lifecycleScope.launch {
            try {
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                val metadata = queryDocumentMetadata(uri)
                val inspection = withContext(Dispatchers.IO) {
                    FirmwareInspector.inspect(metadata.displayName) {
                        contentResolver.openInputStream(uri)
                            ?: throw IOException("Não foi possível abrir o firmware")
                    }
                }

                selectedFirmwareUri = uri
                FlashingSessionStore.setFirmware(
                    name = metadata.displayName,
                    sizeBytes = metadata.sizeBytes,
                    kind = inspection.kind,
                )
                FlashingSessionStore.appendLog(
                    "Firmware selecionado: ${metadata.displayName}" +
                        (metadata.sizeBytes?.let { " (${formatByteCount(it)})" } ?: ""),
                )

                when (inspection.kind) {
                    FirmwareKind.PARAMETER -> parseParameter(uri)
                    FirmwareKind.RAW_DISK_IMAGE -> {
                        FlashingSessionStore.setPartitions(emptyList())
                        FlashingSessionStore.appendLog(
                            "Imagem bruta detectada. O fluxo correto é gravação integral no LBA 0.",
                        )
                    }

                    FirmwareKind.ROCKCHIP_CONTAINER -> {
                        FlashingSessionStore.setPartitions(emptyList())
                        FlashingSessionStore.appendLog(
                            "Contêiner ${inspection.signature} detectado. Gravação bruta bloqueada; " +
                                "ele exige extração RKFW/RKAF.",
                        )
                    }

                    FirmwareKind.UNSUPPORTED -> {
                        FlashingSessionStore.setPartitions(emptyList())
                        FlashingSessionStore.appendLog("Formato de firmware não suportado nesta etapa")
                    }

                    FirmwareKind.NONE -> Unit
                }
            } catch (error: Exception) {
                selectedFirmwareUri = null
                FlashingSessionStore.setFirmware(null, null, FirmwareKind.NONE)
                FlashingSessionStore.appendLog(
                    "Falha ao inspecionar firmware: ${error.message ?: error.javaClass.simpleName}",
                )
            }
        }
    }

    private fun startRawImageFlash() {
        val state = FlashingSessionStore.state.value
        val uri = selectedFirmwareUri
        val sizeBytes = state.firmwareSizeBytes
        when {
            uri == null -> FlashingSessionStore.appendLog("Selecione novamente a imagem de firmware")
            sizeBytes == null || sizeBytes <= 0L ->
                FlashingSessionStore.appendLog("O provedor do arquivo não informou o tamanho da imagem")
            !state.canFlashRawImage ->
                FlashingSessionStore.appendLog("A gravação bruta não está liberada no estado atual")
            else -> UsbHostForegroundService.flashRawImage(this, uri, sizeBytes)
        }
    }

    private suspend fun parseParameter(uri: Uri) = withContext(Dispatchers.IO) {
        try {
            val text = contentResolver.openInputStream(uri)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                ?: throw ParameterParseException("Não foi possível abrir o arquivo")
            val partitions = ParameterParser.parse(text)
            FlashingSessionStore.setPartitions(partitions)
            FlashingSessionStore.appendLog("${partitions.size} partições carregadas do parameter.txt")
        } catch (error: Exception) {
            FlashingSessionStore.setPartitions(emptyList())
            FlashingSessionStore.appendLog("Falha ao analisar parameter.txt: ${error.message}")
        }
    }

    private suspend fun queryDocumentMetadata(uri: Uri): DocumentMetadata = withContext(Dispatchers.IO) {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor: Cursor? = contentResolver.query(uri, projection, null, null, null)
        var displayName: String? = null
        var sizeBytes: Long? = null
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0 && !it.isNull(nameIndex)) displayName = it.getString(nameIndex)
                if (sizeIndex >= 0 && !it.isNull(sizeIndex)) sizeBytes = it.getLong(sizeIndex)
            }
        }

        if (sizeBytes == null || sizeBytes!! <= 0L) {
            sizeBytes = contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                descriptor.length.takeIf { it > 0L }
            }
        }

        DocumentMetadata(
            displayName = displayName ?: uri.lastPathSegment ?: "firmware.img",
            sizeBytes = sizeBytes,
        )
    }

    private data class DocumentMetadata(
        val displayName: String,
        val sizeBytes: Long?,
    )

    companion object {
        private const val STATE_FIRMWARE_URI = "selected_firmware_uri"
    }
}
