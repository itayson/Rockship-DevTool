package com.example.devcontrol

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.devcontrol.data.CommandResult
import com.example.devcontrol.data.SshCommandRequest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as DevControlApp).container

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DevControlScreen(
                        loadDeviceStatus = {
                            val snapshot = container.deviceRepository.currentSnapshot()
                            val root = container.deviceRepository.rootAvailable()
                            "${snapshot.manufacturer} ${snapshot.model}\n" +
                                "Android ${snapshot.androidVersion} (API ${snapshot.apiLevel})\n" +
                                "Root: ${if (root) "disponível" else "indisponível"}"
                        },
                        runLocal = { command ->
                            container.scriptRepository.runScript("Comando local", command)
                        },
                        runSsh = { request ->
                            container.sshRepository.exec(request).getOrThrow()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DevControlScreen(
    loadDeviceStatus: suspend () -> String,
    runLocal: suspend (String) -> CommandResult,
    runSsh: suspend (SshCommandRequest) -> CommandResult,
) {
    val scope = rememberCoroutineScope()
    var deviceStatus by remember { mutableStateOf("Carregando diagnóstico…") }
    var localCommand by remember { mutableStateOf("id") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("22") }
    var username by remember { mutableStateOf("root") }
    var password by remember { mutableStateOf("") }
    var sshCommand by remember { mutableStateOf("uname -a") }
    var output by remember { mutableStateOf("Pronto.") }
    var busy by remember { mutableStateOf(false) }

    fun execute(block: suspend () -> String) {
        scope.launch {
            busy = true
            output = runCatching { block() }
                .getOrElse { error -> "Erro: ${error.message ?: error::class.java.simpleName}" }
            busy = false
        }
    }

    LaunchedEffect(Unit) {
        deviceStatus = runCatching { loadDeviceStatus() }
            .getOrElse { "Falha no diagnóstico: ${it.message}" }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("DevControl", style = MaterialTheme.typography.headlineMedium)
        Text(deviceStatus, style = MaterialTheme.typography.bodyMedium)

        Button(
            onClick = {
                execute {
                    deviceStatus = loadDeviceStatus()
                    deviceStatus
                }
            },
            enabled = !busy,
        ) {
            Text("Atualizar diagnóstico")
        }

        HorizontalDivider()
        Text("Shell local", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = localCommand,
            onValueChange = { localCommand = it },
            label = { Text("Comando") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Button(
            onClick = {
                execute { formatResult(runLocal(localCommand)) }
            },
            enabled = !busy && localCommand.isNotBlank(),
        ) {
            Text("Executar localmente")
        }

        HorizontalDivider()
        Text("SSH", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("Host") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter(Char::isDigit).take(5) },
                label = { Text("Porta") },
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Usuário") },
                modifier = Modifier.weight(2f),
                singleLine = true,
            )
        }
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Senha") },
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
        )
        OutlinedTextField(
            value = sshCommand,
            onValueChange = { sshCommand = it },
            label = { Text("Comando remoto") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Button(
            onClick = {
                execute {
                    val parsedPort = port.toIntOrNull() ?: error("Porta inválida")
                    formatResult(
                        runSsh(
                            SshCommandRequest(
                                host = host.trim(),
                                port = parsedPort,
                                username = username.trim(),
                                password = password,
                                command = sshCommand,
                            ),
                        ),
                    )
                }
            },
            enabled = !busy && host.isNotBlank() && username.isNotBlank() &&
                password.isNotBlank() && sshCommand.isNotBlank(),
        ) {
            Text(if (busy) "Executando…" else "Executar por SSH")
        }

        HorizontalDivider()
        Text("Saída", style = MaterialTheme.typography.titleLarge)
        Text(output, style = MaterialTheme.typography.bodySmall)
    }
}

private fun formatResult(result: CommandResult): String = buildString {
    appendLine("Exit code: ${result.exitCode}")
    appendLine("Duração: ${result.durationMs} ms")
    if (result.stdout.isNotBlank()) {
        appendLine("\nSTDOUT")
        appendLine(result.stdout.trimEnd())
    }
    if (result.stderr.isNotBlank()) {
        appendLine("\nSTDERR")
        appendLine(result.stderr.trimEnd())
    }
}
