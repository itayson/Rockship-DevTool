package com.tayson.rockflash.media

enum class DiskImageKind {
    RAW_DISK,
    HYBRID_ISO,
    PLAIN_ISO,
    WINDOWS_INSTALL_ISO,
    APPLE_DMG,
    ANDROID_SPARSE,
    QCOW2,
    VHD,
    VHDX,
    COMPRESSED_ARCHIVE,
    ROCKCHIP_UPDATE_CONTAINER,
    UNKNOWN,
}

data class DiskImageInspection(
    val kind: DiskImageKind,
    val supportLevel: MediaSupportLevel,
    val description: String,
    val canRawWrite: Boolean,
)

/**
 * Classifica imagens antes de qualquer gravação destrutiva.
 *
 * `header` deve conter preferencialmente os primeiros 64 KiB e `trailer` os
 * últimos 512 bytes. A classificação é conservadora: formatos que exigem
 * extração, criação de filesystem ou conversão nunca são enviados ao disco como
 * se fossem uma imagem bruta.
 */
object DiskImageInspector {
    private const val ISO_PRIMARY_VOLUME_DESCRIPTOR = 0x8000
    private const val ISO_IDENTIFIER_OFFSET = ISO_PRIMARY_VOLUME_DESCRIPTOR + 1

    fun inspect(
        fileName: String,
        sizeBytes: Long,
        header: ByteArray,
        trailer: ByteArray = ByteArray(0),
    ): DiskImageInspection {
        require(sizeBytes >= 0L)
        val lowerName = fileName.lowercase()

        if (header.startsWithAscii("RKFW") || header.startsWithAscii("RKAF")) {
            return result(
                DiskImageKind.ROCKCHIP_UPDATE_CONTAINER,
                MediaSupportLevel.REQUIRES_CONVERSION,
                "Contêiner update.img Rockchip; deve ser extraído e enviado pelo fluxo RockUSB",
            )
        }
        if (header.startsWith(byteArrayOf(0x3A, 0xFF.toByte(), 0x26, 0xED.toByte()))) {
            return result(
                DiskImageKind.ANDROID_SPARSE,
                MediaSupportLevel.REQUIRES_CONVERSION,
                "Android sparse image; precisa ser expandida para RAW antes da gravação",
            )
        }
        if (header.startsWithAscii("QFI\u00fb")) {
            return result(
                DiskImageKind.QCOW2,
                MediaSupportLevel.REQUIRES_CONVERSION,
                "Imagem QCOW2; precisa ser convertida para RAW",
            )
        }
        if (header.startsWithAscii("vhdxfile")) {
            return result(
                DiskImageKind.VHDX,
                MediaSupportLevel.REQUIRES_CONVERSION,
                "Imagem VHDX; precisa ser convertida para RAW",
            )
        }
        if (trailer.startsWithAscii("conectix")) {
            return result(
                DiskImageKind.VHD,
                MediaSupportLevel.REQUIRES_CONVERSION,
                "Imagem VHD dinâmica/fixa; validar e converter para RAW",
            )
        }
        if (trailer.size >= 512 && trailer.copyOfRange(trailer.size - 512, trailer.size).startsWithAscii("koly")) {
            return result(
                DiskImageKind.APPLE_DMG,
                MediaSupportLevel.REQUIRES_CONVERSION,
                "Apple UDIF/DMG; pode conter chunks comprimidos, criptografia e checksums",
            )
        }
        if (header.isCompressedArchive()) {
            return result(
                DiskImageKind.COMPRESSED_ARCHIVE,
                MediaSupportLevel.REQUIRES_CONVERSION,
                "Arquivo comprimido; descompacte ou use um descompressor em streaming",
            )
        }

        val iso9660 = header.hasAsciiAt(ISO_IDENTIFIER_OFFSET, "CD001")
        val mbr = header.size >= 512 &&
            header[510] == 0x55.toByte() && header[511] == 0xAA.toByte()
        val gpt = header.hasAsciiAt(512, "EFI PART")

        if (iso9660 && (mbr || gpt)) {
            return DiskImageInspection(
                kind = DiskImageKind.HYBRID_ISO,
                supportLevel = MediaSupportLevel.SUPPORTED,
                description = "ISO híbrida com tabela de partições; gravação RAW suportada",
                canRawWrite = true,
            )
        }
        if (iso9660) {
            val windowsHint = listOf("windows", "win10", "win11", "cccoma", "microsoft")
                .any(lowerName::contains)
            return result(
                kind = if (windowsHint) DiskImageKind.WINDOWS_INSTALL_ISO else DiskImageKind.PLAIN_ISO,
                support = MediaSupportLevel.REQUIRES_CONVERSION,
                description = if (windowsHint) {
                    "ISO oficial do Windows não é uma imagem USB híbrida; requer particionamento, formatação e cópia dos arquivos"
                } else {
                    "ISO não híbrida; copiar setores pode não produzir uma mídia inicializável"
                },
            )
        }
        if (mbr || gpt || lowerName.endsWith(".img") || lowerName.endsWith(".raw")) {
            return DiskImageInspection(
                kind = DiskImageKind.RAW_DISK,
                supportLevel = MediaSupportLevel.SUPPORTED,
                description = "Imagem bruta de disco; gravação e verificação setor a setor suportadas",
                canRawWrite = true,
            )
        }
        return result(
            DiskImageKind.UNKNOWN,
            MediaSupportLevel.REQUIRES_CONVERSION,
            "Formato não reconhecido; a gravação RAW permanece bloqueada por segurança",
        )
    }

    private fun result(
        kind: DiskImageKind,
        support: MediaSupportLevel,
        description: String,
    ) = DiskImageInspection(
        kind = kind,
        supportLevel = support,
        description = description,
        canRawWrite = false,
    )

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun ByteArray.startsWithAscii(value: String): Boolean =
        startsWith(value.toByteArray(Charsets.ISO_8859_1))

    private fun ByteArray.hasAsciiAt(offset: Int, value: String): Boolean {
        val bytes = value.toByteArray(Charsets.US_ASCII)
        if (offset < 0 || offset + bytes.size > size) return false
        return bytes.indices.all { this[offset + it] == bytes[it] }
    }

    private fun ByteArray.isCompressedArchive(): Boolean =
        startsWith(byteArrayOf(0x1F, 0x8B.toByte())) ||
            startsWith(byteArrayOf(0xFD.toByte(), 0x37, 0x7A, 0x58, 0x5A, 0x00)) ||
            startsWithAscii("BZh") ||
            startsWith(byteArrayOf(0x50, 0x4B, 0x03, 0x04)) ||
            startsWith(byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C))
}
