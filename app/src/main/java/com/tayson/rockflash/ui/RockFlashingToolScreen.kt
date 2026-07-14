package com.tayson.rockflash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tayson.rockflash.core.ConnectionMode
import com.tayson.rockflash.core.FirmwareKind
import com.tayson.rockflash.core.FlashingSessionState
import com.tayson.rockflash.core.FlashingSessionStore
import com.tayson.rockflash.core.PartitionEntry
import com.tayson.rockflash.core.TransferPhase
import com.tayson.rockflash.core.formatByteCount
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val RockFlashingDarkColors: ColorScheme = darkColorScheme()

@Composable
fun RockFlashingToolTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = RockFlashingDarkColors, content = content)
}

@Composable
fun RockFlashingToolRoute(
    onSelectFirmware: () -> Unit,
    onScan: () -> Unit,
    onFlashRawImage: () -> Unit,
    onOpenMediaWriter: () -> Unit,
    onClearLogs: () -> Unit,
) {
    val state by FlashingSessionStore.state.collectAsStateWithLifecycle()
    var showConfirmation by rememberSaveable { mutableStateOf(false) }
    var confirmation by rememberSaveable { mutableStateOf("") }

    RockFlashingToolScreen(
        state = state,
        onSelectFirmware = onSelectFirmware,
        onScan = onScan,
        onFlashRawImage = {
            confirmation = ""
            showConfirmation = true
        },
        onOpenMediaWriter = onOpenMediaWriter,
        onClearLogs = onClearLogs,
    )

    if (showConfirmation) {
        AlertDialog(
            onDismissRequest = {
                if (state.operation?.running != true) showConfirmation = false
            },
            title = { Text("Confirmar gravação integral") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "A imagem será gravada desde o LBA 0 e substituirá a tabela de " +
                            "partições e todos os dados da memória interna.",
                    )
                    Text(
                        state.firmwareName.orEmpty(),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = confirmation,
                        onValueChange = { confirmation = it },
                        label = { Text("Digite FLASH") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = confirmation == FLASH_CONFIRMATION,
                    onClick = {
                        showConfirmation = false
                        confirmation = ""
                        onFlashRawImage()
                    },
                ) {
                    Text("Gravar e verificar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmation = false }) {
                    Text("Cancelar")
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RockFlashingToolScreen(
    state: FlashingSessionState,
    onSelectFirmware: () -> Unit,
    onScan: () -> Unit,
    onFlashRawImage: () -> Unit,
    onOpenMediaWriter: () -> Unit,
    onClearLogs: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("RockFlashingTool", fontWeight = FontWeight.SemiBold)
                        Text(
                            "RockUSB Host, NAND/eMMC e mídia USB",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = onOpenMediaWriter,
                        enabled = state.operation?.running != true,
                    ) {
                        Text("Gravador USB")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ConnectionCard(state, onScan)
            FirmwareCard(state, onSelectFirmware, onFlashRawImage)
            PartitionCard(state, Modifier.height(165.dp))
            TerminalCard(state, onClearLogs, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ConnectionCard(state: FlashingSessionState, onScan: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .background(connectionColor(state.connectionMode), CircleShape),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(state.connectionMode.displayName, fontWeight = FontWeight.SemiBold)
                Text(
                    state.connectedDevice ?: "Conecte um Rockchip por OTG ou use o modo root local",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Button(
                onClick = onScan,
                enabled = state.operation?.running != true,
            ) {
                Text("Detectar")
            }
        }
    }
}

@Composable
private fun FirmwareCard(
    state: FlashingSessionState,
    onSelectFirmware: () -> Unit,
    onFlashRawImage: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Firmware Rockchip", fontWeight = FontWeight.SemiBold)
                    Text(
                        state.firmwareName ?: "Nenhuma imagem selecionada",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (state.firmwareKind != FirmwareKind.NONE) {
                        Text(
                            buildString {
                                append(state.firmwareKind.displayName)
                                state.firmwareSizeBytes?.let { append(" • ${formatByteCount(it)}") }
                            },
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                Button(
                    onClick = onSelectFirmware,
                    enabled = state.operation?.running != true,
                ) {
                    Text("Selecionar")
                }
            }

            when (state.firmwareKind) {
                FirmwareKind.RAW_DISK_IMAGE -> Button(
                    onClick = onFlashRawImage,
                    enabled = state.canFlashRawImage,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Gravar imagem completa no LBA 0")
                }
                FirmwareKind.ROCKCHIP_CONTAINER -> Text(
                    "Contêiner Rockchip detectado; extração RKFW/RKAF necessária.",
                    color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.bodySmall,
                )
                FirmwareKind.UNSUPPORTED -> Text(
                    "Arquivo não suportado para gravação.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                else -> Unit
            }

            state.operation?.let { operation ->
                LinearProgressIndicator(
                    progress = { operation.fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    buildString {
                        append(operation.phase.displayName)
                        if (operation.totalBytes > 0L) {
                            append(": ${formatByteCount(operation.completedBytes)} / ")
                            append(formatByteCount(operation.totalBytes))
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = when (operation.phase) {
                        TransferPhase.FAILED -> MaterialTheme.colorScheme.error
                        TransferPhase.COMPLETED -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
                operation.detail?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}

@Composable
private fun PartitionCard(state: FlashingSessionState, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            Text("Tabela de partições", fontWeight = FontWeight.SemiBold)
            PartitionRow("Nome", "Início", "Tamanho", header = true)
            HorizontalDivider()
            if (state.partitions.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        when (state.firmwareKind) {
                            FirmwareKind.RAW_DISK_IMAGE ->
                                "A imagem bruta contém a própria tabela de partições"
                            FirmwareKind.ROCKCHIP_CONTAINER ->
                                "Extraia o contêiner para listar as partições"
                            else -> "Carregue um parameter.txt"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else {
                LazyColumn {
                    items(state.partitions, key = { it.name }) { partition ->
                        PartitionRow(partition.name, partition.startHex(), partition.sizeLabel())
                    }
                }
            }
        }
    }
}

@Composable
private fun PartitionRow(name: String, start: String, size: String, header: Boolean = false) {
    val rowWeight = if (header) FontWeight.SemiBold else FontWeight.Normal
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(name, modifier = Modifier.weight(1.3f), fontWeight = rowWeight, maxLines = 1)
        Text(
            start,
            modifier = Modifier.weight(1f),
            fontFamily = FontFamily.Monospace,
            fontWeight = rowWeight,
            maxLines = 1,
        )
        Text(size, modifier = Modifier.weight(1f), fontWeight = rowWeight, maxLines = 1)
    }
}

@Composable
private fun TerminalCard(
    state: FlashingSessionState,
    onClearLogs: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val formatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    LaunchedEffect(state.logs.size) {
        if (state.logs.isNotEmpty()) listState.scrollToItem(state.logs.lastIndex)
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0A0D10)),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Terminal", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                TextButton(
                    onClick = onClearLogs,
                    enabled = state.operation?.running != true,
                ) {
                    Text("Limpar")
                }
            }
            HorizontalDivider()
            LazyColumn(modifier = Modifier.fillMaxSize(), state = listState) {
                items(state.logs) { line ->
                    Text(
                        "${formatter.format(Date(line.timestampMillis))}  ${line.message}",
                        color = Color(0xFFB7F7C4),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

private fun connectionColor(mode: ConnectionMode): Color = when (mode) {
    ConnectionMode.NONE -> Color(0xFFE57373)
    ConnectionMode.MASKROM -> Color(0xFFFFB74D)
    ConnectionMode.LOADER -> Color(0xFF66BB6A)
    ConnectionMode.LOCAL_ROOT -> Color(0xFF42A5F5)
    ConnectionMode.ROCKCHIP_UNKNOWN -> Color(0xFFFFCA28)
}

private const val FLASH_CONFIRMATION = "FLASH"
