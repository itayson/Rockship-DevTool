package com.tayson.rockflash.root

import com.tayson.rockflash.model.CommandResult
import java.io.IOException
import java.util.UUID
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
        val failures = mutableListOf<String>()

        for (candidate in candidates.distinct()) {
            // KernelSU e APatch podem fornecer /system/bin/su virtualmente no execve,
            // mesmo quando File.exists()/canExecute() retorna false para o processo do app.
            // Por isso cada caminho precisa ser executado de fato, sem preflight pelo java.io.File.
            val outcome = runProcess(candidate, "id -u", PROBE_TIMEOUT_SECONDS)
            val launchFailure = outcome.launchFailure
            if (launchFailure != null) {
                failures += formatCandidateFailure(
                    candidate = candidate,
                    status = "falha ao iniciar (${launchFailure.kind.name.lowercase()})",
                    output = launchFailure.message,
                )
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
            val status = if (!outcome.completed) {
                "tempo limite aguardando autorização root"
            } else {
                "retornou código ${outcome.exitCode}"
            }
            failures += formatCandidateFailure(candidate, status, outcome.output)
        }

        val state = when {
            sawDenied -> RootState.DENIED
            sawExecutionError -> RootState.ERROR
            else -> RootState.MISSING
        }
        val details = boundedText(
            failures.joinToString(separator = "\n").ifBlank { "Nenhum candidato de root pôde ser executado" },
            MAX_DIAGNOSTIC_OUTPUT,
        )
        RootProbe(state = state, details = details).also { cachedProbe = it }
    }

    fun invalidateProbe() {
        cachedProbe = null
    }

    suspend fun execute(command: String, timeoutSeconds: Long = 30): CommandResult =
        withContext(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            val probe = probe(force = cachedProbe?.available != true)
            if (!probe.available) {
                val diagnostic = buildString {
                    append(probe.summary)
                    if (probe.details.isNotBlank()) append('\n').append(probe.details)
                }
                return@withContext CommandResult(
                    success = false,
                    exitCode = EXIT_ROOT_UNAVAILABLE,
                    output = boundedText(diagnostic, MAX_DIAGNOSTIC_OUTPUT),
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
                        append("Tempo limite excedido após ${timeoutSeconds}s; foi solicitado o encerramento do processo root e de seus descendentes")
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
        val pidFile = "$PID_FILE_PREFIX${UUID.randomUUID()}.pid"
        val wrappedCommand = buildWrappedCommand(command, pidFile)
        val process = try {
            ProcessBuilder(executable, "-c", wrappedCommand)
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
                            val remaining = MAX_CAPTURED_OUTPUT - output.length
                            if (remaining > 0) {
                                val lineWithBreak = "$line\n"
                                output.append(lineWithBreak.take(remaining))
                            }
                        }
                    }
                }
            }
        }

        val completed = runCatching { process.waitFor(timeoutSeconds, TimeUnit.SECONDS) }.getOrDefault(false)
        if (!completed) terminateProcessTree(executable, pidFile, process)
        reader.join(READER_JOIN_MS)

        return ProcessOutcome(
            completed = completed,
            exitCode = if (completed) runCatching { process.exitValue() }.getOrDefault(EXIT_LAUNCH_ERROR) else EXIT_TIMEOUT,
            output = synchronized(output) { output.toString().trim() },
        )
    }

    private fun terminateProcessTree(executable: String, pidFile: String, process: Process) {
        val cleanup = runCatching {
            ProcessBuilder(executable, "-c", buildTerminationCommand(pidFile))
                .redirectErrorStream(true)
                .start()
        }.getOrNull()

        if (cleanup != null) {
            val cleanupCompleted = runCatching {
                cleanup.waitFor(CLEANUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }.getOrDefault(false)
            if (!cleanupCompleted) cleanup.destroyForcibly()
        }

        runCatching { process.destroy() }
        val stopped = runCatching {
            process.waitFor(GRACEFUL_KILL_WAIT_MS, TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        if (!stopped) {
            runCatching { process.destroyForcibly() }
            runCatching { process.waitFor(FORCED_KILL_WAIT_MS, TimeUnit.MILLISECONDS) }
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
        private const val MAX_CANDIDATE_DIAGNOSTIC = 8 * 1024
        private const val MAX_DIAGNOSTIC_OUTPUT = 64 * 1024
        private const val TRUNCATION_MARKER = "\n… [diagnóstico truncado]"
        private const val PROBE_TIMEOUT_SECONDS = 8L
        private const val CLEANUP_TIMEOUT_SECONDS = 5L
        private const val READER_JOIN_MS = 3_000L
        private const val GRACEFUL_KILL_WAIT_MS = 500L
        private const val FORCED_KILL_WAIT_MS = 2_000L
        private const val PID_FILE_PREFIX = "/data/local/tmp/rockflash-"

        val DEFAULT_SU_CANDIDATES = listOf(
            "/system/bin/su",
            "/system/bin/kp",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/debug_ramdisk/su",
            "/data/adb/ksu/bin/su",
            "su",
            "kp",
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

        internal fun formatCandidateFailure(candidate: String, status: String, output: String): String {
            val text = buildString {
                append(candidate)
                append(": ")
                append(status)
                if (output.isNotBlank()) append('\n').append(output.trim())
            }
            return boundedText(text, MAX_CANDIDATE_DIAGNOSTIC)
        }

        internal fun boundedText(text: String, maxChars: Int): String {
            require(maxChars >= 0) { "maxChars não pode ser negativo" }
            if (text.length <= maxChars) return text
            if (maxChars == 0) return ""
            if (maxChars <= TRUNCATION_MARKER.length) return text.take(maxChars)
            return text.take(maxChars - TRUNCATION_MARKER.length) + TRUNCATION_MARKER
        }

        internal fun buildWrappedCommand(command: String, pidFile: String): String {
            val quotedPidFile = shellQuote(pidFile)
            return buildString {
                append("umask 077; printf '%s\\n' \"${'$'}${'$'}\" > ")
                append(quotedPidFile)
                append(" 2>/dev/null || true; ( ")
                append(command)
                append(" ); code=${'$'}?; rm -f ")
                append(quotedPidFile)
                append("; exit ${'$'}code")
            }
        }

        internal fun buildTerminationCommand(pidFile: String): String {
            val quotedPidFile = shellQuote(pidFile)
            return buildString {
                append("pid_file=")
                append(quotedPidFile)
                append("; pid=${'$'}(cat \"${'$'}pid_file\" 2>/dev/null || true); ")
                append("case \"${'$'}pid\" in ''|*[!0-9]*) rm -f \"${'$'}pid_file\"; exit 0;; esac; ")
                append("collect_tree() { for child in ${'$'}(cat /proc/\"${'$'}1\"/task/\"${'$'}1\"/children 2>/dev/null); do collect_tree \"${'$'}child\"; done; printf '%s ' \"${'$'}1\"; }; ")
                append("pids=${'$'}(collect_tree \"${'$'}pid\"); ")
                append("for p in ${'$'}pids; do kill -TERM \"${'$'}p\" 2>/dev/null || true; done; sleep 1; ")
                append("for p in ${'$'}pids; do kill -KILL \"${'$'}p\" 2>/dev/null || true; done; rm -f \"${'$'}pid_file\"")
            }
        }

        private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"
    }
}
