package com.tayson.rockflash.root

import com.topjohnwu.superuser.Shell
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LocalBlockPartition(
    val name: String,
    val devicePath: String,
)

/**
 * Backend local baseado em libsu.
 *
 * Esta fundação expõe descoberta e backup. A restauração/gravação será ligada a
 * uma SafetyGate dedicada para impedir que uma URI, nome ou caminho controlado
 * externamente alcance um comando root destrutivo sem validação explícita.
 */
class LocalNandManager(private val backupRoot: File) {
    suspend fun isRootAvailable(): Boolean = withContext(Dispatchers.IO) {
        val result = Shell.cmd("id -u").exec()
        result.isSuccess && result.out.firstOrNull()?.trim() == "0"
    }

    suspend fun listPartitions(): List<LocalBlockPartition> = withContext(Dispatchers.IO) {
        val script = """
            for p in /dev/block/by-name/* /dev/block/platform/*/by-name/* /dev/block/mtd*; do
              [ -e "${'$'}p" ] || continue
              printf '%s\n' "${'$'}p"
            done
        """.trimIndent()
        val result = Shell.cmd(script).exec()
        check(result.isSuccess) { "Falha ao listar blocos: ${result.err.joinToString("\n")}" }

        result.out
            .asSequence()
            .map { line -> line.trim() }
            .filter { line -> line.isNotEmpty() }
            .filter(::isSafeBlockPath)
            .distinct()
            .sorted()
            .map { path -> LocalBlockPartition(name = path.substringAfterLast('/'), devicePath = path) }
            .toList()
    }

    suspend fun backupPartition(blockDevice: String, outputName: String): File = withContext(Dispatchers.IO) {
        require(isSafeBlockPath(blockDevice)) { "Caminho de bloco não permitido: $blockDevice" }
        require(SAFE_OUTPUT_NAME.matches(outputName)) { "Nome de backup inválido: $outputName" }

        val root = backupRoot.canonicalFile
        check(root.exists() || root.mkdirs()) { "Não foi possível criar a pasta de backup" }
        check(root.isDirectory) { "Destino de backup não é diretório" }

        val outputFile = File(root, outputName).canonicalFile
        require(outputFile.parentFile == root) { "Destino de backup escapou da pasta autorizada" }

        val command = buildString {
            append("dd if=")
            append(shellQuote(blockDevice))
            append(" of=")
            append(shellQuote(outputFile.absolutePath))
            append(" bs=4M conv=fsync")
        }

        var completed = false
        try {
            val result = Shell.cmd(command).exec()
            check(result.isSuccess) {
                "Backup root falhou (${result.code}): ${(result.err + result.out).joinToString("\n")}".trim()
            }
            check(outputFile.isFile && outputFile.length() > 0L) { "Backup concluído sem produzir dados" }
            completed = true
            outputFile
        } finally {
            if (!completed) outputFile.delete()
        }
    }

    private fun isSafeBlockPath(path: String): Boolean =
        path.startsWith("/dev/block/") &&
            !path.contains("..") &&
            SAFE_BLOCK_PATH.matches(path)

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    companion object {
        private val SAFE_BLOCK_PATH = Regex("^/dev/block/[A-Za-z0-9._/-]+${'$'}")
        private val SAFE_OUTPUT_NAME = Regex("^[A-Za-z0-9._-]+\\.img${'$'}")
    }
}
