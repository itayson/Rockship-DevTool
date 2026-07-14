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
import com.tayson.rockflash.core.FlashingSessionStore
import com.tayson.rockflash.firmware.ParameterParseException
import com.tayson.rockflash.firmware.ParameterParser
import com.tayson.rockflash.usb.UsbHostForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RockFlashingToolActivity : ComponentActivity() {
    private val firmwarePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) handleFirmwareSelection(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                        FlashingSessionStore.appendLog("Varredura manual solicitada")
                        UsbHostForegroundService.start(this)
                    },
                    onClearLogs = FlashingSessionStore::clearLogs,
                )
            }
        }
    }

    private fun handleFirmwareSelection(uri: Uri) {
        lifecycleScope.launch {
            val displayName = queryDisplayName(uri) ?: uri.lastPathSegment ?: "firmware.img"
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            FlashingSessionStore.setFirmware(displayName)
            FlashingSessionStore.appendLog("Firmware selecionado: $displayName")

            if (displayName.contains("parameter", ignoreCase = true) || displayName.endsWith(".txt", true)) {
                parseParameter(uri)
            } else {
                FlashingSessionStore.setPartitions(emptyList())
                FlashingSessionStore.appendLog(
                    "Imagem registrada. A extração de update.img será executada pelo módulo de firmware.",
                )
            }
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

    private suspend fun queryDisplayName(uri: Uri): String? = withContext(Dispatchers.IO) {
        val cursor: Cursor = contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        ) ?: return@withContext null
        cursor.use {
            if (!it.moveToFirst()) return@withContext null
            val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index < 0) null else it.getString(index)
        }
    }
}
