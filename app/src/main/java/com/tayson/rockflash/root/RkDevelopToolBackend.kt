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
    ): CommandResult {
        if (!isAvailable()) {
            return CommandResult(
                success = false,
                exitCode = 127,
                output = "rkdeveloptool não encontrado em $binaryPath. Compile/instale pelo Termux ou aguarde o backend nativo completo.",
                durationMs = 0,
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
            append(' ')
            append(command.argument)
        }
        return rootShell.execute(shellCommand, timeoutSeconds = 45)
    }

    enum class ReadOnlyCommand(val argument: String) {
        LIST("ld"),
        READ_CHIP_INFO("rci"),
        READ_FLASH_ID("rid"),
        READ_FLASH_INFO("rfi"),
        READ_CAPABILITY("rcb"),
    }

    companion object {
        const val DEFAULT_BINARY = "/data/data/com.termux/files/usr/bin/rkdeveloptool"
        const val DEFAULT_LIBRARY_PATH = "/data/data/com.termux/files/usr/lib"

        fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"
    }
}
