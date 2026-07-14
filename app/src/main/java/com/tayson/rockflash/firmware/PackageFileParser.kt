package com.tayson.rockflash.firmware

import java.io.File

data class FirmwarePackageEntry(
    val logicalName: String,
    val relativePath: String,
)

data class FirmwareExtractionResult(
    val outputDirectory: File,
    val packageEntries: List<FirmwarePackageEntry>,
    val parameterFile: File?,
)

/**
 * Parser do manifesto `package-file` extraído de update.img.
 *
 * O extrator binário RKFW/RKAF será uma implementação separada para que o
 * parser textual permaneça testável sem Android, JNI ou arquivos gigantes.
 */
object PackageFileParser {
    fun parse(text: String): List<FirmwarePackageEntry> {
        val entries = text.lineSequence()
            .map { line -> line.trim() }
            .filter { line -> line.isNotEmpty() && !line.startsWith('#') }
            .mapIndexed { index, line -> parseLine(index + 1, line) }
            .toList()

        if (entries.isEmpty()) throw ParameterParseException("package-file vazio")
        val duplicate = entries.groupingBy { it.logicalName }.eachCount().entries.firstOrNull { it.value > 1 }
        if (duplicate != null) throw ParameterParseException("Entrada duplicada no package-file: ${duplicate.key}")
        return entries
    }

    private fun parseLine(lineNumber: Int, line: String): FirmwarePackageEntry {
        val columns = line.split(WHITESPACE, limit = 2)
        if (columns.size != 2) throw ParameterParseException("Linha $lineNumber inválida no package-file")

        val logicalName = columns[0].trim()
        val relativePath = columns[1].trim().replace('\\', '/')
        if (!SAFE_LOGICAL_NAME.matches(logicalName)) {
            throw ParameterParseException("Nome lógico inválido na linha $lineNumber: $logicalName")
        }
        if (!isSafeRelativePath(relativePath)) {
            throw ParameterParseException("Caminho inseguro na linha $lineNumber: $relativePath")
        }
        return FirmwarePackageEntry(logicalName = logicalName, relativePath = relativePath)
    }

    private fun isSafeRelativePath(path: String): Boolean =
        path.isNotBlank() &&
            !path.startsWith('/') &&
            !path.contains("://") &&
            path.split('/').none { segment -> segment.isEmpty() || segment == "." || segment == ".." }

    private val WHITESPACE = Regex("\\s+")
    private val SAFE_LOGICAL_NAME = Regex("^[A-Za-z0-9._-]+${'$'}")
}

/** Contrato do extrator binário consolidado RKFW/RKAF. */
interface RockchipFirmwareExtractor {
    suspend fun extract(updateImage: File, destinationDirectory: File): FirmwareExtractionResult
}
