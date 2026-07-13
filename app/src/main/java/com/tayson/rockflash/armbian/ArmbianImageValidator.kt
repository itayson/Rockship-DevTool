package com.tayson.rockflash.armbian

import java.io.File

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

    fun inspect(file: File, role: ImageRole): ImageInspection {
        if (!file.isFile || file.length() <= 0L) {
            return ImageInspection(false, false, "Arquivo inexistente ou vazio")
        }

        val name = file.name.lowercase()
        return when (role) {
            ImageRole.UBOOT_MAIN -> {
                val nameMatches = name == "u-boot-main.img" || name.startsWith("u-boot-main")
                val sizeMatches = file.length() in (128L * 1024L)..(16L * ONE_MIB)
                ImageInspection(
                    accepted = nameMatches && sizeMatches,
                    legacyKernelLikely = false,
                    message = when {
                        !nameMatches -> "O bootstrap deve ser o arquivo oficial u-boot-main.img"
                        !sizeMatches -> "Tamanho incompatível com um bootstrap U-Boot"
                        else -> "Bootstrap RK322x reconhecido"
                    },
                )
            }

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
}
