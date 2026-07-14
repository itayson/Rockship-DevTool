package com.tayson.rockflash.root

import com.tayson.rockflash.model.CommandResult

data class RkBackendStatus(
    val root: RootProbe,
    val binaryPath: String? = null,
    val details: String,
) {
    val ready: Boolean
        get() = root.available && !binaryPath.isNullOrBlank()

    val summary: String
        get() = when {
            !root.available -> root.summary
            binaryPath.isNullOrBlank() -> "Root disponível, mas rkdeveloptool não foi encontrado. Instale-o no Termux ou aguarde o backend USB nativo."
            else -> "Backend root pronto: $binaryPath"
        }
}

class RkDevelopToolBackend(
    private val rootShell: RootShell = RootShell(),
    private val binaryCandidates: List<String> = DEFAULT_BINARY_CANDIDATES,
) {
    suspend fun status(force: Boolean = false): RkBackendStatus {
        val root = rootShell.probe(force)
        if (!root.available) return RkBackendStatus(root = root, details = root.details)

        for (candidate in binaryCandidates.distinct()) {
            val check = rootShell.execute(
                "test -x ${shellQuote(candidate)} && printf '%s\\n' ${shellQuote(candidate)}",
                timeoutSeconds = 8,
            )
            if (check.success) {
                return RkBackendStatus(
                    root = root,
                    binaryPath = candidate,
                    details = "rkdeveloptool executável",
                )
            }
            if (check.exitCode in INFRASTRUCTURE_EXIT_CODES) {
                return RkBackendStatus(root = root, details = check.output)
            }
        }

        return RkBackendStatus(
            root = root,
            details = "Caminhos verificados: ${binaryCandidates.joinToString()}",
        )
    }

    suspend fun isAvailable(): Boolean = status().ready

    suspend fun runReadOnly(
        command: ReadOnlyCommand,
        usbDeviceNode: String?,
    ): CommandResult = runCommand(
        arguments = listOf(command.argument),
        usbDeviceNode = usbDeviceNode,
        timeoutSeconds = 120,
    )

    suspend fun backupFlash(
        usbDeviceNode: String?,
        outputPath: String,
        sectorCount: Long,
    ): CommandResult {
        if (sectorCount <= 0L || sectorCount > 0xFFFFFFFFL) {
            return failure("Quantidade de setores inválida: $sectorCount")
        }
        if (outputPath.isBlank()) return failure("Caminho de backup inválido")

        return runCommand(
            arguments = listOf("rl", "0x0", sectorCount.toString(), outputPath),
            usbDeviceNode = usbDeviceNode,
            timeoutSeconds = 43_200,
        )
    }

    suspend fun runWrite(
        command: WriteCommand,
        usbDeviceNode: String?,
        filePath: String? = null,
        partitionName: String? = null,
        startSector: Long? = null,
        confirmation: String,
    ): CommandResult {
        val expectedConfirmation = if (command == WriteCommand.ERASE_FLASH) "APAGAR" else "FLASH"
        if (confirmation != expectedConfirmation) {
            return failure("Confirmação inválida. Digite $expectedConfirmation exatamente.")
        }

        val arguments = try {
            buildWriteArguments(command, filePath, partitionName, startSector)
        } catch (error: IllegalArgumentException) {
            return failure(error.message ?: "Parâmetros inválidos")
        }

        if (command.requiresFile) {
            val path = requireNotNull(filePath)
            val fileCheck = rootShell.execute("test -s ${shellQuote(path)}", timeoutSeconds = 10)
            if (!fileCheck.success) {
                if (fileCheck.exitCode in INFRASTRUCTURE_EXIT_CODES) return fileCheck
                return failure("Arquivo inexistente, vazio ou inacessível: $path\n${fileCheck.output}")
            }
        }

        return runCommand(
            arguments = arguments,
            usbDeviceNode = usbDeviceNode,
            timeoutSeconds = if (command.longRunning) 21_600 else 300,
        )
    }

    internal fun buildWriteArguments(
        command: WriteCommand,
        filePath: String?,
        partitionName: String?,
        startSector: Long?,
    ): List<String> {
        fun requiredFile(): String = requireNotNull(filePath?.takeIf { it.isNotBlank() }) {
            "Selecione um arquivo antes de executar ${command.name}."
        }

        return when (command) {
            WriteCommand.DOWNLOAD_BOOT -> listOf("db", requiredFile())
            WriteCommand.UPGRADE_LOADER -> listOf("ul", requiredFile())
            WriteCommand.WRITE_RAW_LBA -> {
                val sector = requireNotNull(startSector) { "Informe o setor inicial." }
                require(sector >= 0) { "O setor inicial não pode ser negativo." }
                listOf("wl", sector.toString(), requiredFile())
            }
            WriteCommand.WRITE_PARTITION -> {
                val partition = requireNotNull(partitionName?.trim()?.takeIf { it.isNotEmpty() }) {
                    "Informe o nome da partição."
                }
                require(PARTITION_PATTERN.matches(partition)) {
                    "Nome de partição inválido. Use letras, números, ponto, hífen ou sublinhado."
                }
                listOf("wlx", partition, requiredFile())
            }
            WriteCommand.WRITE_GPT -> listOf("gpt", requiredFile())
            WriteCommand.WRITE_PARAMETER -> listOf("prm", requiredFile())
            WriteCommand.ERASE_FLASH -> listOf("ef")
            WriteCommand.RESET_DEVICE -> listOf("rd")
        }
    }

    private suspend fun runCommand(
        arguments: List<String>,
        usbDeviceNode: String?,
        timeoutSeconds: Long,
    ): CommandResult {
        val backendStatus = status(force = false)
        if (!backendStatus.ready) {
            val exitCode = if (backendStatus.root.available) {
                EXIT_BACKEND_UNAVAILABLE
            } else {
                RootShell.EXIT_ROOT_UNAVAILABLE
            }
            return failure(backendStatus.summary, exitCode = exitCode)
        }
        val binaryPath = requireNotNull(backendStatus.binaryPath)

        if (!usbDeviceNode.isNullOrBlank() && !USB_NODE_PATTERN.matches(usbDeviceNode)) {
            return failure("Node USB inválido: $usbDeviceNode")
        }

        val libraryPath = libraryPathFor(binaryPath)
        val shellCommand = buildString {
            append("export PATH=/data/data/com.termux/files/usr/bin:/data/user/0/com.termux/files/usr/bin:/system/bin:/system/xbin:${'$'}PATH; ")
            append("export LD_LIBRARY_PATH=")
            append(shellQuote(libraryPath))
            append("; exec ")
            append(shellQuote(binaryPath))
            arguments.forEach {
                append(' ')
                append(shellQuote(it))
            }
        }
        return rootShell.execute(shellCommand, timeoutSeconds)
    }

    private fun libraryPathFor(binaryPath: String): String =
        binaryPath.substringBeforeLast("/bin/") + "/lib"

    private fun failure(message: String, exitCode: Int = 2): CommandResult = CommandResult(
        success = false,
        exitCode = exitCode,
        output = message,
        durationMs = 0,
    )

    enum class ReadOnlyCommand(val argument: String) {
        LIST("ld"),
        READ_CHIP_INFO("rci"),
        READ_FLASH_ID("rid"),
        READ_FLASH_INFO("rfi"),
        READ_CAPABILITY("rcb"),
        PRINT_PARTITIONS("ppt"),
    }

    enum class WriteCommand(
        val requiresFile: Boolean,
        val longRunning: Boolean,
    ) {
        DOWNLOAD_BOOT(requiresFile = true, longRunning = false),
        UPGRADE_LOADER(requiresFile = true, longRunning = true),
        WRITE_RAW_LBA(requiresFile = true, longRunning = true),
        WRITE_PARTITION(requiresFile = true, longRunning = true),
        WRITE_GPT(requiresFile = true, longRunning = true),
        WRITE_PARAMETER(requiresFile = true, longRunning = true),
        ERASE_FLASH(requiresFile = false, longRunning = true),
        RESET_DEVICE(requiresFile = false, longRunning = false),
    }

    companion object {
        const val DEFAULT_BINARY = "/data/data/com.termux/files/usr/bin/rkdeveloptool"
        const val EXIT_BACKEND_UNAVAILABLE = 125

        val DEFAULT_BINARY_CANDIDATES = listOf(
            DEFAULT_BINARY,
            "/data/user/0/com.termux/files/usr/bin/rkdeveloptool",
        )

        private val PARTITION_PATTERN = Regex("[A-Za-z0-9_.-]{1,64}")
        private val USB_NODE_PATTERN = Regex("^/dev/bus/usb/\\d{3}/\\d{3}$")
        private val INFRASTRUCTURE_EXIT_CODES = setOf(
            RootShell.EXIT_TIMEOUT,
            RootShell.EXIT_ROOT_UNAVAILABLE,
            RootShell.EXIT_LAUNCH_ERROR,
            EXIT_BACKEND_UNAVAILABLE,
        )

        fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"
    }
}
