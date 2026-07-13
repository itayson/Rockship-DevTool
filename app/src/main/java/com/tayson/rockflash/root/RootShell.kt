package com.tayson.rockflash.root

import com.tayson.rockflash.model.CommandResult
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RootShell {
    suspend fun execute(command: String, timeoutSeconds: Long = 30): CommandResult =
        withContext(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()

            val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                return@withContext CommandResult(
                    success = false,
                    exitCode = -1,
                    output = "Tempo limite excedido após ${timeoutSeconds}s",
                    durationMs = System.currentTimeMillis() - startedAt,
                )
            }

            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            val exitCode = process.exitValue()
            CommandResult(
                success = exitCode == 0,
                exitCode = exitCode,
                output = output,
                durationMs = System.currentTimeMillis() - startedAt,
            )
        }
}
