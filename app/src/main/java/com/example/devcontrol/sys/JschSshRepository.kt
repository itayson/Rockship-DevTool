package com.example.devcontrol.sys

import com.example.devcontrol.data.CommandResult
import com.example.devcontrol.data.SftpConnectionConfig
import com.example.devcontrol.data.SftpEntry
import com.example.devcontrol.data.SshCommandRequest
import com.example.devcontrol.repo.SshRepository
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets

class JschSshRepository : SshRepository {

    override suspend fun exec(request: SshCommandRequest): Result<CommandResult> = withContext(Dispatchers.IO) {
        runCatching {
            val started = System.currentTimeMillis()
            val session = openSession(
                host = request.host,
                port = request.port,
                username = request.username,
                password = request.password,
                privateKeyPem = request.privateKeyPem
            )

            session.use { s ->
                val channel = (s.openChannel("exec") as ChannelExec)
                val stdout = ByteArrayOutputStream()
                val stderr = ByteArrayOutputStream()
                channel.command = request.command
                channel.setOutputStream(stdout)
                channel.setErrStream(stderr)
                channel.connect()

                while (!channel.isClosed) {
                    Thread.sleep(100)
                }

                val result = CommandResult(
                    command = request.command,
                    exitCode = channel.exitStatus,
                    stdout = stdout.toString(StandardCharsets.UTF_8.name()),
                    stderr = stderr.toString(StandardCharsets.UTF_8.name()),
                    durationMs = System.currentTimeMillis() - started
                )
                channel.disconnect()
                result
            }
        }
    }

    override suspend fun listFiles(config: SftpConnectionConfig, remotePath: String): Result<List<SftpEntry>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val session = openSession(
                    host = config.host,
                    port = config.port,
                    username = config.username,
                    password = config.password,
                    privateKeyPem = config.privateKeyPem
                )

                session.use { s ->
                    val channel = (s.openChannel("sftp") as ChannelSftp)
                    channel.connect()
                    try {
                        @Suppress("UNCHECKED_CAST")
                        val entries = channel.ls(remotePath) as Vector<ChannelSftp.LsEntry>
                        entries.map {
                            SftpEntry(
                                name = it.filename,
                                longName = it.longname,
                                isDirectory = it.attrs.isDir
                            )
                        }
                    } finally {
                        channel.disconnect()
                    }
                }
            }
        }

    override suspend fun uploadText(config: SftpConnectionConfig, remotePath: String, content: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val session = openSession(
                    host = config.host,
                    port = config.port,
                    username = config.username,
                    password = config.password,
                    privateKeyPem = config.privateKeyPem
                )

                session.use { s ->
                    val channel
