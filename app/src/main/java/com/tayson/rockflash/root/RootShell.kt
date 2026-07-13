package com.tayson.rockflash.root

import com.tayson.rockflash.model.CommandResult
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RootShell {
    suspend fun execute(command: String, timeoutSeconds: Long = 30): CommandResult =
        withContext(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = StringBuilder()
            val reader = thread(name = "rockflash-root-output", isDaemon = true) {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(output) {
                            if (output.length < MAX_CAPTURED_OUTPUT) output.appendLine(line)
                        }
                    }
                }
            }

            val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!completed) process.destroyForcibly()
            reader.join(3_000)
            val capturedOutput = synchronized(output) { output.toString().trim() }

            if (!completed) {
                return@withContext CommandResult(
                    success = false,
                    exitCode = -1,
                    output = buildString {
                        append("Tempo limite excedido após ${timeoutSeconds}s")
                        if (capturedOutput.isNotBlank()) append("\n").append(capturedOutput)
                    },
                    durationMs = System.currentTimeMillis() - startedAt,
                )
            }

            val exitCode = process.exitValue()
            CommandResult(
                success = exitCode == 0,
                exitCode = exitCode,
                output = capturedOutput,
                durationMs = System.currentTimeMillis() - startedAt,
            )
        }

    companion object {
        private const val MAX_CAPTURED_OUTPUT = 2 * 1024 * 1024
    }
}
