package com.tayson.rockflash.root

import com.tayson.rockflash.model.CommandResult
import java.io.File

data class RemovableBlockDevice(
    val node: String,
    val sizeBytes: Long,
    val removable: Boolean,
    val model: String,
    val vendor: String,
    val serial: String,
) {
    val label: String
        get() = buildString {
            append(node)
            append(" • ")
            append(formatBytes(sizeBytes))
            val description = listOf(vendor.trim(), model.trim()).filter { it.isNotBlank() }.joinToString(" ")
            if (description.isNotBlank()) append(" • $description")
            if (serial.isNotBlank()) append(" • S/N ${serial.trim()}")
        }

    fun samePhysicalIdentity(other: RemovableBlockDevice): Boolean =
        node == other.node &&
            sizeBytes == other.sizeBytes &&
            removable == other.removable &&
            model.trim() == other.model.trim() &&
            vendor.trim() == other.vendor.trim() &&
            serial.trim() == other.serial.trim()

    companion object {
        private fun formatBytes(bytes: Long): String = when {
            bytes >= 1024L * 1024L * 1024L -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024L * 1024L -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
            else -> "$bytes B"
        }
    }
}

class UsbBlockBackend(
    private val rootShell: RootShell = RootShell(),
) {
    suspend fun listCandidates(): Result<List<RemovableBlockDevice>> {
        val result = rootShell.execute(LIST_SCRIPT, timeoutSeconds = 15)
        if (!result.success) {
            return Result.failure(IllegalStateException(result.output.ifBlank { "Falha ao listar dispositivos USB" }))
        }

        val devices = result.output.lineSequence()
            .mapNotNull(::parseLine)
            .filter { it.removable }
            .toList()
        return Result.success(devices)
    }

    suspend fun writeImage(
        imagePath: String,
        target: RemovableBlockDevice,
        verify: Boolean = true,
    ): CommandResult {
        requireValidNode(target.node)
        require(target.removable) { "O destino não foi identificado como removível" }

        val current = currentMetadata(target.node).getOrElse { error ->
            return failure(error.message ?: "Não foi possível revalidar o dispositivo USB")
        }
        if (!target.samePhysicalIdentity(current)) {
            return failure(
                "O dispositivo USB mudou desde a seleção. Esperado: ${target.label}; atual: ${current.label}. Detecte o pendrive novamente.",
            )
        }

        val image = File(imagePath)
        if (!image.isFile || image.length() <= 0L) {
            return failure("Imagem inexistente ou vazia: $imagePath")
        }
        if (target.sizeBytes > 0 && image.length() > target.sizeBytes) {
            return failure("A imagem é maior que o dispositivo USB selecionado")
        }

        val quotedImage = RkDevelopToolBackend.shellQuote(image.absolutePath)
        val quotedNode = RkDevelopToolBackend.shellQuote(target.node)
        val verifyCommand = if (verify) {
            "command -v cmp >/dev/null 2>&1 || { echo 'ERRO: cmp indisponível; verificação obrigatória não pode ser executada' >&2; exit 127; }; " +
                "cmp -n ${image.length()} $quotedImage $quotedNode;"
        } else {
            ""
        }

        val command = """
            set -e
            export PATH=/data/data/com.termux/files/usr/bin:/system/bin:/system/xbin:${'$'}PATH
            test -b $quotedNode
            test -s $quotedImage
            for p in ${target.node}*; do umount "${'$'}p" 2>/dev/null || true; done
            sync
            dd if=$quotedImage of=$quotedNode bs=4M conv=fsync
            sync
            $verifyCommand
            echo ROCKFLASH_USB_WRITE_OK
        """.trimIndent()

        return rootShell.execute(command, timeoutSeconds = 21_600)
    }

    private suspend fun currentMetadata(node: String): Result<RemovableBlockDevice> {
        requireValidNode(node)
        val blockName = node.substringAfterLast('/')
        val script = metadataScriptFor(blockName)
        val result = rootShell.execute(script, timeoutSeconds = 10)
        if (!result.success) {
            return Result.failure(IllegalStateException(result.output.ifBlank { "Dispositivo USB não encontrado" }))
        }
        val current = result.output.lineSequence().mapNotNull(::parseLine).firstOrNull()
            ?: return Result.failure(IllegalStateException("Metadados atuais do dispositivo USB são inválidos"))
        return Result.success(current)
    }

    private fun parseLine(line: String): RemovableBlockDevice? {
        val parts = line.split('|', limit = 6)
        if (parts.size < 6) return null
        val sectors = parts[1].toLongOrNull() ?: return null
        return RemovableBlockDevice(
            node = parts[0],
            sizeBytes = sectors * 512L,
            removable = parts[2] == "1",
            model = parts[3],
            vendor = parts[4],
            serial = parts[5],
        )
    }

    private fun requireValidNode(node: String) {
        require(BLOCK_NODE_PATTERN.matches(node)) {
            "Destino inválido. Somente /dev/block/sdX ou /dev/sdX são aceitos."
        }
    }

    private fun metadataScriptFor(blockName: String): String = """
        p=/sys/block/$blockName
        [ -e "${'$'}p" ] || { echo 'Dispositivo removido' >&2; exit 1; }
        if [ -b "/dev/block/$blockName" ]; then node="/dev/block/$blockName"; elif [ -b "/dev/$blockName" ]; then node="/dev/$blockName"; else exit 1; fi
        sectors=${'$'}(cat "${'$'}p/size" 2>/dev/null || echo 0)
        removable=${'$'}(cat "${'$'}p/removable" 2>/dev/null || echo 0)
        model=${'$'}(cat "${'$'}p/device/model" 2>/dev/null | tr '|\r\n' '   ')
        vendor=${'$'}(cat "${'$'}p/device/vendor" 2>/dev/null | tr '|\r\n' '   ')
        serial=${'$'}(cat "${'$'}p/device/serial" 2>/dev/null | tr '|\r\n' '   ')
        echo "${'$'}node|${'$'}sectors|${'$'}removable|${'$'}model|${'$'}vendor|${'$'}serial"
    """.trimIndent()

    private fun failure(message: String): CommandResult = CommandResult(
        success = false,
        exitCode = 2,
        output = message,
        durationMs = 0,
    )

    companion object {
        private val BLOCK_NODE_PATTERN = Regex("^/dev/(block/)?sd[a-z]$")

        private val LIST_SCRIPT = """
            for p in /sys/block/sd*; do
                [ -e "${'$'}p" ] || continue
                n=${'$'}(basename "${'$'}p")
                if [ -b "/dev/block/${'$'}n" ]; then node="/dev/block/${'$'}n"; elif [ -b "/dev/${'$'}n" ]; then node="/dev/${'$'}n"; else continue; fi
                sectors=${'$'}(cat "${'$'}p/size" 2>/dev/null || echo 0)
                removable=${'$'}(cat "${'$'}p/removable" 2>/dev/null || echo 0)
                model=${'$'}(cat "${'$'}p/device/model" 2>/dev/null | tr '|\r\n' '   ')
                vendor=${'$'}(cat "${'$'}p/device/vendor" 2>/dev/null | tr '|\r\n' '   ')
                serial=${'$'}(cat "${'$'}p/device/serial" 2>/dev/null | tr '|\r\n' '   ')
                echo "${'$'}node|${'$'}sectors|${'$'}removable|${'$'}model|${'$'}vendor|${'$'}serial"
            done
        """.trimIndent()
    }
}
