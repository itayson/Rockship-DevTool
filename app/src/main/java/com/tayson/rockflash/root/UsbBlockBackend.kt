package com.tayson.rockflash.root

import com.tayson.rockflash.model.CommandResult
import java.io.File

data class RemovableBlockDevice(
    val node: String,
    val sizeBytes: Long,
    val removable: Boolean,
    val model: String,
) {
    val label: String
        get() = buildString {
            append(node)
            append(" • ")
            append(formatBytes(sizeBytes))
            if (model.isNotBlank()) append(" • ${model.trim()}")
        }

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
            "if command -v cmp >/dev/null 2>&1; then cmp -n ${image.length()} $quotedImage $quotedNode; else echo 'AVISO: cmp indisponível; verificação binária não executada'; fi;"
        } else {
            ""
        }

        val command = """
            set -e
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

    private fun parseLine(line: String): RemovableBlockDevice? {
        val parts = line.split('|', limit = 4)
        if (parts.size < 4) return null
        val sectors = parts[1].toLongOrNull() ?: return null
        return RemovableBlockDevice(
            node = parts[0],
            sizeBytes = sectors * 512L,
            removable = parts[2] == "1",
            model = parts[3],
        )
    }

    private fun requireValidNode(node: String) {
        require(BLOCK_NODE_PATTERN.matches(node)) {
            "Destino inválido. Somente /dev/block/sdX ou /dev/sdX são aceitos."
        }
    }

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
                model=${'$'}(cat "${'$'}p/device/model" 2>/dev/null | tr -d '\r\n')
                echo "${'$'}node|${'$'}sectors|${'$'}removable|${'$'}model"
            done
        """.trimIndent()
    }
}
