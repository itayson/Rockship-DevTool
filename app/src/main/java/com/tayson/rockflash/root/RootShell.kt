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

internal enum class LaunchFailureKind {
    MISSING,
    SECURITY,
    OTHER,
}

class RootShell(
    private val candidates: List<String> = DEFAULT_SU_CANDIDATES,
) {
    @Volatile
    private var cachedProbe: RootProbe? = null

    suspend fun probe(force: Boolean = false): RootProbe = withContext(Dispatchers.IO) {
        if (!force) cachedProbe?.let { return@withContext it }

        var sawDenied = false
        var sawExecutionError = false
        var lastFailure = "Nenhum executável su foi localizado"

        for (candidate in candidates.distinct()) {
            if (candidate.startsWith('/') && !File(candidate).canExecute()) continue

            val outcome = runProcess(candidate, "id -u", PROBE_TIMEOUT_SECONDS)
            val launchFailure = outcome.launchFailure
            if (launchFailure != null) {
                lastFailure = launchFailure.message
                when (launchFailure.kind) {
                    LaunchFailureKind.MISSING -> Unit
                    LaunchFailureKind.SECURITY,
                    LaunchFailureKind.OTHER,
                    -> sawExecutionError = true
                }
                continue
            }

            val uid = outcome.output.lineSequence().map(String::trim).firstOrNull { it.matches(Regex("\\d+")) }
            if (outcome.completed && outcome.exitCode == 0 && uid == "0") {
                return@withContext RootProbe(
                    state = RootState.AVAILABLE,
                    executable = candidate,
                    details = "uid=0",
                ).also { cachedProbe = it }
            }

            sawDenied = true
            lastFailure = outcome.output.ifBlank {
                if (!outcome.completed) "Tempo limite aguardando autorização root" else "su retornou código ${outcome.exitCode}"
            }
        }

        val state = when {
            sawDenied -> RootState.DENIED
            sawExecutionError -> RootState.ERROR
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
            val launchFailure = outcome.launchFailure
            if (launchFailure != null) {
                invalidateProbe()
                return@withContext CommandResult(
                    success = false,
                    exitCode = EXIT_LAUNCH_ERROR,
                    output = "Não foi possível iniciar ${probe.executable}: ${launchFailure.message}",
                    durationMs = System.currentTimeMillis() - startedAt,
                )
            }

            if (!outcome.completed) {
                return@withContext CommandResult(
                    success = false,
                    exitCode = EXIT_TIMEOUT,
                    output = buildString {
                        append("Tempo limite excedido após ${timeoutSeconds}s; o processo e seus descendentes foram encerrados")
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
        } catch (error: Throwable) {
            val kind = classifyLaunchFailure(error)
            return ProcessOutcome(
                launchFailure = LaunchFailure(
                    kind = kind,
                    message = error.message ?: error.javaClass.simpleName,
                ),
            )
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
        if (!completed) terminateProcessTree(process)
        reader.join(READER_JOIN_MS)

        return ProcessOutcome(
            completed = completed,
            exitCode = if (completed) runCatching { process.exitValue() }.getOrDefault(EXIT_LAUNCH_ERROR) else EXIT_TIMEOUT,
            output = synchronized(output) { output.toString().trim() },
        )
    }

    private fun terminateProcessTree(process: Process) {
        val descendants = mutableListOf<ProcessHandle>()
        runCatching {
            process.toHandle().descendants().forEach { descendants += it }
        }

        descendants.asReversed().forEach { handle ->
            runCatching { if (handle.isAlive) handle.destroy() }
        }
        runCatching { process.destroy() }

        val parentStopped = runCatching {
            process.waitFor(GRACEFUL_KILL_WAIT_MS, TimeUnit.MILLISECONDS)
        }.getOrDefault(false)

        if (!parentStopped || descendants.any { it.isAlive }) {
            descendants.asReversed().forEach { handle ->
                runCatching { if (handle.isAlive) handle.destroyForcibly() }
            }
            runCatching { process.destroyForcibly() }
            runCatching { process.waitFor(FORCED_KILL_WAIT_MS, TimeUnit.MILLISECONDS) }
        }

        val deadline = System.currentTimeMillis() + FORCED_KILL_WAIT_MS
        while (descendants.any { it.isAlive } && System.currentTimeMillis() < deadline) {
            Thread.sleep(PROCESS_POLL_MS)
        }
    }

    private data class LaunchFailure(
        val kind: LaunchFailureKind,
        val message: String,
    )

    private data class ProcessOutcome(
        val completed: Boolean = false,
        val exitCode: Int = EXIT_LAUNCH_ERROR,
        val output: String = "",
        val launchFailure: LaunchFailure? = null,
    )

    companion object {
        const val EXIT_TIMEOUT = -1
        const val EXIT_ROOT_UNAVAILABLE = 126
        const val EXIT_LAUNCH_ERROR = 127

        private const val MAX_CAPTURED_OUTPUT = 2 * 1024 * 1024
        private const val PROBE_TIMEOUT_SECONDS = 8L
        private const val READER_JOIN_MS = 3_000L
        private const val GRACEFUL_KILL_WAIT_MS = 500L
        private const val FORCED_KILL_WAIT_MS = 2_000L
        private const val PROCESS_POLL_MS = 25L

        val DEFAULT_SU_CANDIDATES = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/debug_ramdisk/su",
            "/data/adb/ksu/bin/su",
            "su",
        )

        internal fun classifyLaunchFailure(error: Throwable): LaunchFailureKind {
            if (error is SecurityException) return LaunchFailureKind.SECURITY
            if (error is IOException) {
                val message = error.message.orEmpty().lowercase()
                if ("error=2" in message || "no such file" in message || "enoent" in message) {
                    return LaunchFailureKind.MISSING
                }
            }
            return LaunchFailureKind.OTHER
        }
    }
}
