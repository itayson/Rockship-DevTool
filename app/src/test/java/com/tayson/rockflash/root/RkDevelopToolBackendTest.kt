package com.tayson.rockflash.root

import org.junit.Assert.assertEquals
import org.junit.Test

class RkDevelopToolBackendTest {
    @Test
    fun shellQuoteEscapesSingleQuotes() {
        assertEquals("'a'\\''b'", RkDevelopToolBackend.shellQuote("a'b"))
    }

    @Test
    fun destructiveCommandsAreNotExposed() {
        val commands = RkDevelopToolBackend.ReadOnlyCommand.entries.map { it.argument }
        assertEquals(listOf("ld", "rci", "rid", "rfi", "rcb"), commands)
    }
}
