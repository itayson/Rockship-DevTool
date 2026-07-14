package com.tayson.rockflash.root

import java.io.File
import java.io.IOException
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

    @Test
    fun executableThatRejectsAccessIsReportedAsDenied() = runBlocking {
        val fakeSu = File.createTempFile("fake-su-denied", ".sh").apply {
            writeText("#!/bin/sh\necho denied\nexit 1\n")
            setExecutable(true)
            deleteOnExit()
        }

        val probe = RootShell(candidates = listOf(fakeSu.absolutePath)).probe(force = true)

        assertEquals(RootState.DENIED, probe.state)
        assertTrue(probe.details.contains("denied"))
    }

    @Test
    fun executableLaunchFailureIsReportedAsError() = runBlocking {
        val directory = File.createTempFile("fake-su-directory", ".tmp").apply {
            delete()
            mkdirs()
            setExecutable(true)
            deleteOnExit()
        }

        val probe = RootShell(candidates = listOf(directory.absolutePath)).probe(force = true)

        assertEquals(RootState.ERROR, probe.state)
    }

    @Test
    fun classifiesMissingAndSecurityLaunchFailures() {
        assertEquals(
            LaunchFailureKind.MISSING,
            RootShell.classifyLaunchFailure(IOException("Cannot run program su: error=2, No such file or directory")),
        )
        assertEquals(
            LaunchFailureKind.SECURITY,
            RootShell.classifyLaunchFailure(SecurityException("blocked")),
        )
        assertEquals(
            LaunchFailureKind.OTHER,
            RootShell.classifyLaunchFailure(IOException("Permission denied")),
        )
    }
}
