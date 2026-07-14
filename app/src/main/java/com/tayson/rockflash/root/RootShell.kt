package com.tayson.rockflash.root

import com.tayson.rockflash.model.CommandResult
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class RootState {
    AVAILABLE,
    MISSING,
    DENIED,
    ERROR,
}

data class RootProbe(
    val state: RootState,
    val executable: String? = null,
    val details: String,
) {
    val available: Boolean
        get() = state == RootState.AVAILABLE && !executable.isNullOrBlank()

    val summary: String
        get() = when (state) {
            RootState.AVAILABLE -> "Root disponível por ${executable.orEmpty()}"
            RootState.MISSING -> "Root não encontrado. Instale/configure Magisk, KernelSU ou APatch e conceda acesso ao RockFlash."
            RootState.DENIED -> "O gerenciador de root negou ou não respondeu ao RockFlash. Abra o gerenciador e conceda permissão."
            RootState.ERROR -> "Falha ao testar root: $details"
        }
}

class RootShell(
    private val candidates: List<String> = DEFAULT_SU_CANDIDATES,
) {
    @Volatile
    private var cachedProbe: RootProbe? = null

    suspend fun probe(force: Boolean = false): RootProbe = withContext(Dispatchers.IO) {
        if (!force) cachedProbe?.let { return@withContext it }

        var launchedAtLeastOnce = false
        var lastFailure = "Nenhum executável su foi localizado"
        var denied = false

        for (candidate in candidates.distinct()) {
            if (candidate.startsWith('/') && !File(candidate).canExecute()) continue

            val outcome = runProcess(candidate, "id -u", PROBE_TIMEOUT_SECONDS)
            if (outcome.launchError != null) {
                lastFailure = outcome.launchError
                continue
            }

            launchedAtLeastOnce = true
            val uid = outcome.output.lineSequence().map(String::trim).firstOrNull { it.matches(Regex("\\d+")) }
            if (outcome.completed && outcome.exitCode == 0 && uid == "0") {
                return@withContext RootProbe(
                    state = RootState.AVAILABLE,
                    executable = candidate,
                    details = "uid=0",
                ).also { cachedProbe = it }
            }

            denied = true
            lastFailure = outcome.output.ifBlank {
                if (!outcome.completed) "Tempo limite aguardando autorização root" else "su retornou código ${outcome.exitCode}"
            }
        }

        val state = when {
            denied -> RootState.DENIED
            launchedAtLeastOnce -> RootState.ERROR
            else -> RootState.MISSING
        }
        RootProbe(state = state, details = lastFailure).also { cachedProbe = it }
    }

    fun invalidateProbe() {
        cachedProbe = null
    }

    suspend fun execute(command: String, timeoutSeconds: Long = 30): CommandResult =
        withContext(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            val probe = probe(force = cachedProbe?.available != true)
            if (!probe.available) {
                return@withContext CommandResult(
                    success = false,
                    exitCode = EXIT_ROOT_UNAVAILABLE,
                    output = probe.summary,
                    durationMs = System.currentTimeMillis() - startedAt,
                )
            }

            val outcome = runProcess(requireNotNull(probe.executable), command, timeoutSeconds)
            if (outcome.launchError != null) {
                invalidateProbe()
                return@withContext CommandResult(
                    success = false,
                    exitCode = EXIT_LAUNCH_ERROR,
                    output = "Não foi possível iniciar ${probe.executable}: ${outcome.launchError}",
                    durationMs = System.currentTimeMillis() - startedAt,
                )
            }

            if (!outcome.completed) {
                return@withContext CommandResult(
                    success = false,
                    exitCode = EXIT_TIMEOUT,
                    output = buildString {
                        append("Tempo limite excedido após ${timeoutSeconds}s")
                        if (outcome.output.isNotBlank()) append('\n').append(outcome.output)
                    },
                    durationMs = System.currentTimeMillis() - startedAt,
                )
            }

            CommandResult(
                success = outcome.exitCode == 0,
                exitCode = outcome.exitCode,
                output = outcome.output,
                durationMs = System.currentTimeMillis() - startedAt,
            )
        }

    private fun runProcess(executable: String, command: String, timeoutSeconds: Long): ProcessOutcome {
        val process = try {
            ProcessBuilder(executable, "-c", command)
                .redirectErrorStream(true)
                .start()
        } catch (error: IOException) {
            return ProcessOutcome(launchError = error.message ?: error.javaClass.simpleName)
        } catch (error: SecurityException) {
            return ProcessOutcome(launchError = error.message ?: "Execução bloqueada pelo Android")
        } catch (error: Throwable) {
            return ProcessOutcome(launchError = error.message ?: error.javaClass.simpleName)
        }

        val output = StringBuilder()
        val reader = thread(name = "rockflash-root-output", isDaemon = true) {
            runCatching {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(output) {
                            if (output.length < MAX_CAPTURED_OUTPUT) output.appendLine(line)
                        }
                    }
                }
            }
        }

        val completed = runCatching { process.waitFor(timeoutSeconds, TimeUnit.SECONDS) }.getOrDefault(false)
        if (!completed) process.destroyForcibly()
        reader.join(READER_JOIN_MS)

        return ProcessOutcome(
            completed = completed,
            exitCode = if (completed) runCatching { process.exitValue() }.getOrDefault(EXIT_LAUNCH_ERROR) else EXIT_TIMEOUT,
            output = synchronized(output) { output.toString().trim() },
        )
    }

    private data class ProcessOutcome(
        val completed: Boolean = false,
        val exitCode: Int = EXIT_LAUNCH_ERROR,
        val output: String = "",
        val launchError: String? = null,
    )

    companion object {
        const val EXIT_TIMEOUT = -1
        const val EXIT_ROOT_UNAVAILABLE = 126
        const val EXIT_LAUNCH_ERROR = 127

        private const val MAX_CAPTURED_OUTPUT = 2 * 1024 * 1024
        private const val PROBE_TIMEOUT_SECONDS = 8L
        private const val READER_JOIN_MS = 3_000L

        val DEFAULT_SU_CANDIDATES = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/debug_ramdisk/su",
            "/data/adb/ksu/bin/su",
            "su",
        )
    }
}
