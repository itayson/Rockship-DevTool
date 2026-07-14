package com.tayson.rockflash.firmware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PackageFileParserTest {
    @Test
    fun `parses package-file entries`() {
        val entries = PackageFileParser.parse(
            """
                package-file package-file
                bootloader MiniLoaderAll.bin
                parameter parameter.txt
                boot Image/boot.img
            """.trimIndent(),
        )

        assertEquals(4, entries.size)
        assertEquals("MiniLoaderAll.bin", entries[1].relativePath)
        assertEquals("parameter", entries[2].logicalName)
    }

    @Test
    fun `rejects traversal path`() {
        assertThrows(ParameterParseException::class.java) {
            PackageFileParser.parse("boot ../../boot.img")
        }
    }

    @Test
    fun `rejects duplicate logical names`() {
        assertThrows(ParameterParseException::class.java) {
            PackageFileParser.parse("boot boot.img\nboot boot-debug.img")
        }
    }
}
