package com.tayson.rockflash.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import com.tayson.rockflash.core.TransferPhase
import com.tayson.rockflash.core.formatByteCount
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun UniversalMediaWriterRoute(
    onBack: () -> Unit,
    onScan: () -> Unit,
    onSelectImage: () -> Unit,
    onSelectTarget: (Int?) -> Unit,
    onRequestPermission: (Int) -> Unit,
    onWrite: () -> Unit,
    onClearLogs: () -> Unit,
) {
    val state by MediaWriterStore.state.collectAsStateWithLifecycle()
    var showConfirmation by rememberSaveable { mutableStateOf(false) }
    var confirmation by rememberSaveable { mutableStateOf("") }

    UniversalMediaWriterScreen(
        state = state,
        onBack = onBack,
        onScan = onScan,
        onSelectImage = onSelectImage,
        onSelectTarget = onSelectTarget,
        onRequestPermission = onRequestPermission,
        onWrite = {
            confirmation = ""
            showConfirmation = true
        },
        onClearLogs = onClearLogs,
    )

    if (showConfirmation) {
        val target = state.selectedTarget
        AlertDialog(
            onDismissRequest = {
                if (state.operation?.running != true) showConfirmation = false
            },
            title = { Text("Apagar e gravar unidade USB") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Todos os dados da unidade selecionada serão substituídos pela imagem.")
                    Text(
                        target?.modelLabel ?: "Destino não selecionado",
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        target?.vidPid.orEmpty(),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = confirmation,
                        onValueChange = { confirmation = it },
                        label = { Text("Digite APAGAR") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = confirmation == ERASE_CONFIRMATION && state.canWrite,
                    onClick = {
                        showConfirmation = false
                        confirmation = ""
                        onWrite()
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
private fun UniversalMediaWriterScreen(
    state: MediaWriterState,
    onBack: () -> Unit,
    onScan: () -> Unit,
    onSelectImage: () -> Unit,
    onSelectTarget: (Int?) -> Unit,
    onRequestPermission: (Int) -> Unit,
    onWrite: () -> Unit,
    onClearLogs: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Gravador USB", fontWeight = FontWeight.SemiBold)
                        Text("Pendrive, HDD, SSD e leitor SD", style = MaterialTheme.typography.labelSmall)
                    }
                },
                navigationIcon = {
                    TextButton(onClick = onBack, enabled = state.operation?.running != true) {
                        Text("Voltar")
                    }
                },
                actions = {
                    TextButton(onClick = onScan, enabled = state.operation?.running != true) {
                        Text("Detectar")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ImageCard(state, onSelectImage)
            TargetListCard(
                state = state,
                onSelectTarget = onSelectTarget,
                onRequestPermission = onRequestPermission,
                modifier = Modifier.weight(1f),
            )
            WriteCard(state = state, onWrite = onWrite)
            MediaTerminalCard(
                state = state,
                onClearLogs = onClearLogs,
                modifier = Modifier.weight(0.8f),
            )
        }
    }
}

@Composable
private fun ImageCard(state: MediaWriterState, onSelectImage: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Imagem de origem", fontWeight = FontWeight.SemiBold)
                Text(
                    state.imageName ?: "Nenhuma imagem selecionada",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
                state.imageInspection?.let { inspection ->
                    Text(
                        buildString {
                            append(inspection.kind.name)
                            state.imageSizeBytes?.let { append(" • ${formatByteCount(it)}") }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (inspection.canRawWrite) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                    Text(inspection.description, style = MaterialTheme.typography.labelSmall)
                }
            }
            Button(
                onClick = onSelectImage,
                enabled = state.operation?.running != true,
            ) {
                Text("Selecionar")
            }
        }
    }
}

@Composable
private fun TargetListCard(
    state: MediaWriterState,
    onSelectTarget: (Int?) -> Unit,
    onRequestPermission: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            Text("Unidades USB detectadas", fontWeight = FontWeight.SemiBold)
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            if (state.targets.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Conecte uma unidade USB e toque em Detectar")
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.targets, key = { it.deviceId }) { target ->
                        TargetRow(
                            target = target,
                            selected = state.selectedDeviceId == target.deviceId,
                            operationRunning = state.operation?.running == true,
                            onSelect = { onSelectTarget(target.deviceId) },
                            onRequestPermission = { onRequestPermission(target.deviceId) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TargetRow(
    target: UsbMediaTargetState,
    selected: Boolean,
    operationRunning: Boolean,
    onSelect: () -> Unit,
    onRequestPermission: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RadioButton(
                selected = selected,
                onClick = onSelect,
                enabled = target.ready && !operationRunning,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(target.modelLabel, fontWeight = FontWeight.SemiBold)
                Text(
                    buildString {
                        append(target.vidPid)
                        append(" • ${target.transport}")
                        target.capacityBytes?.let { capacity ->
                            if (capacity <= Long.MAX_VALUE.toULong()) {
                                append(" • ${formatByteCount(capacity.toLong())}")
                            } else {
                                append(" • > 8 EiB")
                            }
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    target.initializationError ?: target.reason,
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        target.initializationError != null -> MaterialTheme.colorScheme.error
                        target.ready -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.tertiary
                    },
                )
            }
            if (target.supportLevel == MediaSupportLevel.SUPPORTED && !target.permissionGranted) {
                TextButton(onClick = onRequestPermission, enabled = !operationRunning) {
                    Text("Autorizar")
                }
            }
        }
    }
}

@Composable
private fun WriteCard(state: MediaWriterState, onWrite: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onWrite,
                enabled = state.canWrite,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Gravar imagem na unidade USB")
            }
            state.operation?.let { operation ->
                LinearProgressIndicator(
                    progress = { operation.fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${operation.phase.displayName}: ${formatByteCount(operation.completedBytes)} / " +
                        formatByteCount(operation.totalBytes),
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
private fun MediaTerminalCard(
    state: MediaWriterState,
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
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

private const val ERASE_CONFIRMATION = "APAGAR"
