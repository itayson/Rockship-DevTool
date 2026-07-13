package com.tayson.rockflash.root

import com.tayson.rockflash.model.CommandResult

class RkDevelopToolBackend(
    private val rootShell: RootShell = RootShell(),
    private val binaryPath: String = DEFAULT_BINARY,
    private val libraryPath: String = DEFAULT_LIBRARY_PATH,
) {
    suspend fun isAvailable(): Boolean =
        rootShell.execute("test -x ${shellQuote(binaryPath)}", timeoutSeconds = 5).success

    suspend fun runReadOnly(
        command: ReadOnlyCommand,
        usbDeviceNode: String?,
    ): CommandResult = runCommand(
        arguments = listOf(command.argument),
        usbDeviceNode = usbDeviceNode,
        timeoutSeconds = 120,
    )

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
            if (!fileCheck.success) return failure("Arquivo inexistente, vazio ou inacessível: $path")
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
        if (!isAvailable()) {
            return failure(
                "rkdeveloptool não encontrado em $binaryPath. Instale o binário pelo Termux/root antes de usar o backend de gravação.",
                exitCode = 127,
            )
        }

        if (!usbDeviceNode.isNullOrBlank()) {
            val permissionResult = rootShell.execute(
                "chmod 666 ${shellQuote(usbDeviceNode)}",
                timeoutSeconds = 5,
            )
            if (!permissionResult.success) {
                return permissionResult.copy(
                    output = "Falha ao liberar acesso a $usbDeviceNode\n${permissionResult.output}",
                )
            }
        }

        val shellCommand = buildString {
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
        const val DEFAULT_LIBRARY_PATH = "/data/data/com.termux/files/usr/lib"
        private val PARTITION_PATTERN = Regex("[A-Za-z0-9_.-]{1,64}")

        fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"
    }
}
