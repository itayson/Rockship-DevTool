package com.tayson.rockflash.firmware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ParameterParserTest {
    @Test
    fun `parses hexadecimal Rockchip mtdparts`() {
        val text = """
            FIRMWARE_VER:12.0.0
            MACHINE_MODEL:RK322x
            CMDLINE:mtdparts=rk29xxnand:0x00002000@0x00002000(misc),0x00008000@0x00004000(boot),-@0x0000c000(system:grow)
        """.trimIndent()

        val partitions = ParameterParser.parse(text)

        assertEquals(3, partitions.size)
        assertEquals("misc", partitions[0].name)
        assertEquals(0x2000L, partitions[0].startLba)
        assertEquals(0x2000L, partitions[0].sectorCount)
        assertEquals("boot", partitions[1].name)
        assertNull(partitions[2].sectorCount)
        assertEquals("system:grow", partitions[2].name)
    }

    @Test
    fun `parses decimal sectors`() {
        val partitions = ParameterParser.parse(
            "CMDLINE:mtdparts=rk29xxnand:4096@8192(boot),8192@12288(system)",
        )

        assertEquals(8192L, partitions[0].startLba)
        assertEquals(4096L, partitions[0].sectorCount)
    }

    @Test
    fun `rejects duplicate names`() {
        assertThrows(ParameterParseException::class.java) {
            ParameterParser.parse(
                "CMDLINE:mtdparts=rk29xxnand:0x100@0x0(boot),0x100@0x100(boot)",
            )
        }
    }

    @Test
    fun `rejects overlapping ranges`() {
        assertThrows(ParameterParseException::class.java) {
            ParameterParser.parse(
                "CMDLINE:mtdparts=rk29xxnand:0x100@0x0(a),0x100@0x80(b)",
            )
        }
    }

    @Test
    fun `requires remainder partition to be last`() {
        assertThrows(ParameterParseException::class.java) {
            ParameterParser.parse(
                "CMDLINE:mtdparts=rk29xxnand:-@0x0(system),0x100@0x100(backup)",
            )
        }
    }
}
