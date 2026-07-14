package com.tayson.rockflash.firmware

import com.tayson.rockflash.core.PartitionEntry

class ParameterParseException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

/** Parser estrito do campo mtdparts usado nos arquivos parameter.txt Rockchip. */
object ParameterParser {
    private val partitionPattern = Regex(
        pattern = """(?<size>-|(?:0x)?[0-9a-fA-F]+)@(?<offset>(?:0x)?[0-9a-fA-F]+)\((?<name>[^)]+)\)(?<flags>[^,\s]*)""",
    )

    fun parse(text: String): List<PartitionEntry> {
        val markerIndex = text.indexOf(MTD_PARTS_MARKER, ignoreCase = true)
        if (markerIndex < 0) throw ParameterParseException("Campo mtdparts não encontrado")

        val payload = text.substring(markerIndex + MTD_PARTS_MARKER.length)
        val deviceSeparator = payload.indexOf(':')
        if (deviceSeparator < 0) throw ParameterParseException("Separador do dispositivo mtdparts não encontrado")

        val partitionText = payload.substring(deviceSeparator + 1)
            .lineSequence()
            .firstOrNull()
            .orEmpty()
            .trim()
        if (partitionText.isEmpty()) throw ParameterParseException("Lista de partições vazia")

        val partitions = partitionText.split(',').mapIndexed { index, rawSegment ->
            val segment = rawSegment.trim()
            if (segment.isEmpty()) throw ParameterParseException("Segmento vazio na posição ${index + 1}")
            val match = partitionPattern.matchEntire(segment)
                ?: throw ParameterParseException("Segmento de partição inválido: $segment")

            val name = match.groups["name"]?.value?.trim().orEmpty()
            if (name.isEmpty()) throw ParameterParseException("Partição sem nome")

            val offsetToken = match.groups["offset"]?.value
                ?: throw ParameterParseException("Offset ausente em $name")
            val sizeToken = match.groups["size"]?.value
                ?: throw ParameterParseException("Tamanho ausente em $name")
            val startLba = parseUnsigned(offsetToken, "offset de $name")
            val sectorCount = if (sizeToken == "-") null else parseUnsigned(sizeToken, "tamanho de $name")
            if (sectorCount == 0L) throw ParameterParseException("Partição $name possui tamanho zero")

            PartitionEntry(
                name = name,
                startLba = startLba,
                sectorCount = sectorCount,
                flags = match.groups["flags"]?.value.orEmpty(),
            )
        }

        validate(partitions)
        return partitions
    }

    private fun validate(partitions: List<PartitionEntry>) {
        val duplicate = partitions.groupingBy { it.name }.eachCount().entries.firstOrNull { it.value > 1 }
        if (duplicate != null) throw ParameterParseException("Partição duplicada: ${duplicate.key}")

        partitions.forEachIndexed { index, partition ->
            if (partition.sectorCount == null && index != partitions.lastIndex) {
                throw ParameterParseException("Partição de tamanho restante deve ser a última: ${partition.name}")
            }
            partition.sectorCount?.let { sectors ->
                try {
                    Math.multiplyExact(sectors, SECTOR_SIZE_BYTES)
                    Math.addExact(partition.startLba, sectors)
                } catch (error: ArithmeticException) {
                    throw ParameterParseException("Overflow no intervalo da partição ${partition.name}", error)
                }
            }
        }

        val sorted = partitions.sortedBy { it.startLba }
        var previousEnd = 0L
        sorted.forEachIndexed { index, partition ->
            if (index > 0 && partition.startLba < previousEnd) {
                throw ParameterParseException("Sobreposição detectada em ${partition.name}")
            }
            previousEnd = partition.endLbaExclusive ?: Long.MAX_VALUE
        }
    }

    private fun parseUnsigned(token: String, field: String): Long = try {
        val normalized = token.trim()
        val radix = if (normalized.startsWith("0x", ignoreCase = true)) 16 else 10
        val digits = if (radix == 16) normalized.substring(2) else normalized
        digits.toLong(radix).also {
            if (it < 0L) throw NumberFormatException("valor negativo")
        }
    } catch (error: NumberFormatException) {
        throw ParameterParseException("Valor inválido para $field: $token", error)
    }

    private const val MTD_PARTS_MARKER = "mtdparts="
    private const val SECTOR_SIZE_BYTES = 512L
}
