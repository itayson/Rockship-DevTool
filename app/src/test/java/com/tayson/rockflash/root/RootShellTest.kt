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
    fun executableWithoutUidZeroIsReportedAsDenied() = runBlocking {
        val probe = RootShell(candidates = listOf("/bin/sh")).probe(force = true)

        assertEquals(RootState.DENIED, probe.state)
        assertFalse(probe.available)
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

    @Test
    fun wrappedCommandTracksRootShellPidAndCleansMarker() {
        val command = RootShell.buildWrappedCommand("printf test", "/data/local/tmp/test pid")

        assertTrue(command.contains("printf '%s\\n' \"$$\""))
        assertTrue(command.contains("( printf test )"))
        assertTrue(command.contains("rm -f '/data/local/tmp/test pid'"))
    }

    @Test
    fun terminationCommandCollectsProcDescendantsBeforeKilling() {
        val command = RootShell.buildTerminationCommand("/data/local/tmp/test.pid")

        assertTrue(command.contains("/proc/\"$1\"/task/\"$1\"/children"))
        assertTrue(command.contains("kill -TERM"))
        assertTrue(command.contains("kill -KILL"))
        assertTrue(command.contains("rm -f \"$pid_file\""))
    }
}
