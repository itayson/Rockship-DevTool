package com.tayson.rockflash.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RkDevelopToolBackendTest {
    private val backend = RkDevelopToolBackend()

    @Test
    fun shellQuoteEscapesSingleQuotes() {
        assertEquals("'a'\\''b'", RkDevelopToolBackend.shellQuote("a'b"))
    }

    @Test
    fun readCommandsIncludePartitionListing() {
        val commands = RkDevelopToolBackend.ReadOnlyCommand.entries.map { it.argument }
        assertEquals(listOf("ld", "rci", "rid", "rfi", "rcb", "ppt"), commands)
    }

    @Test
    fun buildsRawImageWriteCommand() {
        assertEquals(
            listOf("wl", "0", "/tmp/armbian.img"),
            backend.buildWriteArguments(
                RkDevelopToolBackend.WriteCommand.WRITE_RAW_LBA,
                "/tmp/armbian.img",
                null,
                0,
            ),
        )
    }

    @Test
    fun buildsPartitionWriteCommand() {
        assertEquals(
            listOf("wlx", "boot", "/tmp/boot.img"),
            backend.buildWriteArguments(
                RkDevelopToolBackend.WriteCommand.WRITE_PARTITION,
                "/tmp/boot.img",
                "boot",
                null,
            ),
        )
    }

    @Test
    fun rejectsUnsafePartitionName() {
        assertThrows(IllegalArgumentException::class.java) {
            backend.buildWriteArguments(
                RkDevelopToolBackend.WriteCommand.WRITE_PARTITION,
                "/tmp/boot.img",
                "boot;reboot",
                null,
            )
        }
    }
}
