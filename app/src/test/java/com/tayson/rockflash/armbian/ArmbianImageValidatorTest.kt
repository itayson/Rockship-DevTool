package com.tayson.rockflash.armbian

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArmbianImageValidatorTest {
    @Test
    fun acceptsOfficialUbootMainNameAndSize() {
        val file = temporaryFile("u-boot-main.img", 1024 * 1024)
        assertTrue(ArmbianImageValidator.inspect(file, ImageRole.UBOOT_MAIN).accepted)
    }

    @Test
    fun rejectsExtractedGenericUbootName() {
        val file = temporaryFile("u-boot-extracted.img", 4 * 1024 * 1024)
        assertFalse(ArmbianImageValidator.inspect(file, ImageRole.UBOOT_MAIN).accepted)
    }

    @Test
    fun detectsLegacyArmbianFromFilename() {
        val file = temporaryFile("Armbian_legacy_4.4_rk322x.img", 129 * 1024 * 1024)
        val result = ArmbianImageValidator.inspect(file, ImageRole.ARMBIAN)
        assertTrue(result.accepted)
        assertTrue(result.legacyKernelLikely)
    }

    private fun temporaryFile(name: String, size: Int): File {
        val directory = createTempDir(prefix = "rockflash-")
        val file = File(directory, name)
        file.outputStream().use { output ->
            output.write(0)
            output.channel.truncate(size.toLong())
        }
        file.deleteOnExit()
        directory.deleteOnExit()
        return file
    }
}
