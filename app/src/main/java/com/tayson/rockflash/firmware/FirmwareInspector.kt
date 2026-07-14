package com.tayson.rockflash.firmware

import com.tayson.rockflash.core.FirmwareKind
import java.io.IOException
import java.io.InputStream

data class FirmwareInspection(
    val kind: FirmwareKind,
    val signature: String?,
)

/**
 * Distingue imagens brutas de contêineres Rockchip antes de habilitar escrita no LBA 0.
 *
 * Gravar um RKFW/RKAF consolidado como se fosse disco bruto corromperia a flash.
 */
object FirmwareInspector {
    fun inspect(fileName: String, openStream: () -> InputStream): FirmwareInspection {
        val lowerName = fileName.lowercase()
        if (lowerName.endsWith(".txt") || lowerName.contains("parameter")) {
            return FirmwareInspection(FirmwareKind.PARAMETER, null)
        }

        val signature = openStream().use { stream ->
            val header = ByteArray(SIGNATURE_SIZE)
            var offset = 0
            while (offset < header.size) {
                val read = stream.read(header, offset, header.size - offset)
                if (read < 0) break
                if (read == 0) throw IOException("Leitura sem progresso ao inspecionar firmware")
                offset += read
            }
            if (offset < header.size) null else header.toString(Charsets.US_ASCII)
        }

        return when (signature) {
            RKFW_SIGNATURE, RKAF_SIGNATURE, RKFP_SIGNATURE ->
                FirmwareInspection(FirmwareKind.ROCKCHIP_CONTAINER, signature)

            else -> when {
                lowerName.endsWith(".img") -> FirmwareInspection(FirmwareKind.RAW_DISK_IMAGE, signature)
                else -> FirmwareInspection(FirmwareKind.UNSUPPORTED, signature)
            }
        }
    }

    private const val SIGNATURE_SIZE = 4
    private const val RKFW_SIGNATURE = "RKFW"
    private const val RKAF_SIGNATURE = "RKAF"
    private const val RKFP_SIGNATURE = "RKFP"
}
