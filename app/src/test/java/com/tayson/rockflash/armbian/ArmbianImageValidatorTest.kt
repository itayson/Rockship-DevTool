package com.tayson.rockflash.armbian

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArmbianImageValidatorTest {
    @Test
    fun acceptsOfficialUbootMainNameAndSize() {
        val file = temporaryFile("u-boot-main.img", 1024L * 1024L)
        assertTrue(ArmbianImageValidator.inspect(file, ImageRole.UBOOT_MAIN).accepted)
    }

    @Test
    fun rejectsExtractedGenericUbootName() {
        val file = temporaryFile("u-boot-extracted.img", 4L * 1024L * 1024L)
        assertFalse(ArmbianImageValidator.inspect(file, ImageRole.UBOOT_MAIN).accepted)
    }

    @Test
    fun detectsLegacyArmbianFromFilename() {
        val file = temporaryFile("Armbian_legacy_4.4_rk322x.img", 129L * 1024L * 1024L)
        val result = ArmbianImageValidator.inspect(file, ImageRole.ARMBIAN)
        assertTrue(result.accepted)
        assertTrue(result.legacyKernelLikely)
    }

    private fun temporaryFile(name: String, size: Long): File {
        val directory = createTempDir(prefix = "rockflash-")
        val file = File(directory, name)
        RandomAccessFile(file, "rw").use { it.setLength(size) }
        file.deleteOnExit()
        directory.deleteOnExit()
        return file
    }
}
