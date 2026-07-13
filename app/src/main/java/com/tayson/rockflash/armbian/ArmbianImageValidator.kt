package com.tayson.rockflash.armbian

import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

enum class ImageRole {
    ARMBIAN,
    MULTITOOL,
    UBOOT_MAIN,
}

data class ImageInspection(
    val accepted: Boolean,
    val legacyKernelLikely: Boolean,
    val message: String,
)

object ArmbianImageValidator {
    private const val ONE_MIB = 1024L * 1024L
    private const val MAX_BOOTSTRAP_SCAN_BYTES = 8 * 1024 * 1024
    private const val MIN_NON_PADDING_BYTES = 256

    /* Digests can be added when an upstream release publishes immutable hashes. */
    private val trustedUbootSha256 = emptySet<String>()

    fun inspect(file: File, role: ImageRole): ImageInspection {
        if (!file.isFile || file.length() <= 0L) {
            return ImageInspection(false, false, "Arquivo inexistente ou vazio")
        }

        val name = file.name.lowercase()
        return when (role) {
            ImageRole.UBOOT_MAIN -> inspectUbootMain(file, name)
            ImageRole.MULTITOOL -> {
                val nameMatches = "multitool" in name
                val sizeMatches = file.length() >= 64L * ONE_MIB
                ImageInspection(
                    accepted = nameMatches && sizeMatches,
                    legacyKernelLikely = false,
                    message = when {
                        !nameMatches -> "O nome do arquivo não identifica uma imagem Multitool"
                        !sizeMatches -> "A imagem Multitool parece pequena demais"
                        else -> "Imagem Multitool reconhecida"
                    },
                )
            }

            ImageRole.ARMBIAN -> {
                val nameMatches = "armbian" in name || "rk322" in name
                val sizeMatches = file.length() >= 128L * ONE_MIB
                val legacy = "legacy" in name || "4.4." in name || "4.4-" in name || "4_4" in name
                ImageInspection(
                    accepted = nameMatches && sizeMatches,
                    legacyKernelLikely = legacy,
                    message = when {
                        !nameMatches -> "O nome do arquivo não identifica uma imagem Armbian/RK322x"
                        !sizeMatches -> "A imagem Armbian parece pequena demais"
                        legacy -> "Imagem Armbian legacy provável; adequada ao fluxo steP-nand"
                        else -> "Imagem Armbian reconhecida, mas o nome não confirma kernel legacy 4.4"
                    },
                )
            }
        }
    }

    private fun inspectUbootMain(file: File, name: String): ImageInspection {
        val nameMatches = name == "u-boot-main.img" || name.startsWith("u-boot-main-")
        val sizeMatches = file.length() in (128L * 1024L)..(16L * ONE_MIB) && file.length() % 512L == 0L
        val digest = sha256(file)
        val trustedDigest = digest in trustedUbootSha256
        val structureMatches = hasExpectedUbootStructure(file)
        val contentMatches = trustedDigest || structureMatches

        return ImageInspection(
            accepted = nameMatches && sizeMatches && contentMatches,
            legacyKernelLikely = false,
            message = when {
                !nameMatches -> "O bootstrap deve ser o arquivo oficial u-boot-main.img"
                !sizeMatches -> "Tamanho ou alinhamento incompatível com o bootstrap U-Boot"
                !contentMatches -> "O conteúdo não apresenta a estrutura esperada de U-Boot Rockchip; SHA-256: $digest"
                trustedDigest -> "Bootstrap RK322x reconhecido por SHA-256 confiável"
                else -> "Bootstrap RK322x reconhecido por estrutura binária; SHA-256: $digest"
            },
        )
    }

    private fun hasExpectedUbootStructure(file: File): Boolean {
        val scanLength = minOf(file.length(), MAX_BOOTSTRAP_SCAN_BYTES.toLong()).toInt()
        if (scanLength <= 0) return false
        val data = ByteArray(scanLength)
        val count = file.inputStream().buffered().use { input ->
            var total = 0
            while (total < scanLength) {
                val read = input.read(data, total, scanLength - total)
                if (read <= 0) break
                total += read
            }
            total
        }
        if (count <= 0) return false

        var nonPaddingBytes = 0
        val distinct = BooleanArray(256)
        for (index in 0 until count) {
            val value = data[index].toInt() and 0xFF
            if (value != 0 && value != 0xFF) nonPaddingBytes++
            distinct[value] = true
        }
        if (nonPaddingBytes < MIN_NON_PADDING_BYTES || distinct.count { it } < 16) return false

        val searchable = String(data, 0, count, StandardCharsets.ISO_8859_1).lowercase()
        val hasUboot = "u-boot" in searchable
        val hasRockchipMarker = listOf("rockchip", "rk322", "rk32", "bootcmd", "fdt", "rknand")
            .any { it in searchable }
        return hasUboot && hasRockchipMarker
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
