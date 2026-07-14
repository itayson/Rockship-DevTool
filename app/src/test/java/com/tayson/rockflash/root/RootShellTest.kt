package com.tayson.rockflash.root

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootShellTest {
    @Test
    fun missingSuReturnsStructuredResultInsteadOfThrowing() = runBlocking {
        val shell = RootShell(candidates = listOf("/definitely/not/a/real/su"))

        val probe = shell.probe(force = true)
        val result = shell.execute("id -u")

        assertEquals(RootState.MISSING, probe.state)
        assertFalse(result.success)
        assertEquals(RootShell.EXIT_ROOT_UNAVAILABLE, result.exitCode)
        assertTrue(result.output.contains("Root não encontrado"))
    }
}
