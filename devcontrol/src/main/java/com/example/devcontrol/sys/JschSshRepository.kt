package com.example.devcontrol.sys

import com.example.devcontrol.data.CommandResult
import com.example.devcontrol.data.SftpConnectionConfig
import com.example.devcontrol.data.SftpEntry
import com.example.devcontrol.data.SshCommandRequest
import com.example.devcontrol.repo.SshRepository
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Properties
import java.util.Vector

class JschSshRepository : SshRepository {

    override suspend fun exec(request: SshCommandRequest): Result<CommandResult> = withContext(Dispatchers.IO) {
        runCatching {
            require(request.command.isNotBlank()) { "O comando SSH não pode estar vazio" }
            val started = System.currentTimeMillis()
            val session = openSession(
                host = request.host,
                port = request.port,
                username = request.username,
                password = request.password,
                privateKeyPem = request.privateKeyPem,
            )

            try {
                val channel = session.openChannel("exec") as ChannelExec
                val stdout = ByteArrayOutputStream()
                val stderr = ByteArrayOutputStream()
                try {
                    channel.setCommand(request.command)
                    channel.setInputStream(null)
                    channel.setOutputStream(stdout)
                    channel.setErrStream(stderr)
                    channel.connect(CONNECT_TIMEOUT_MS)

                    val deadline = System.currentTimeMillis() + COMMAND_TIMEOUT_MS
                    while (!channel.isClosed && System.currentTimeMillis() < deadline) {
                        Thread.sleep(100)
                    }
                    if (!channel.isClosed) {
                        channel.disconnect()
                        error("Tempo limite executando comando SSH")
                    }

                    CommandResult(
                        command = request.command,
                        exitCode = channel.exitStatus,
                        stdout = stdout.toString(StandardCharsets.UTF_8.name()),
                        stderr = stderr.toString(StandardCharsets.UTF_8.name()),
                        durationMs = System.currentTimeMillis() - started,
                    )
                } finally {
                    if (channel.isConnected) channel.disconnect()
                }
            } finally {
                if (session.isConnected) session.disconnect()
            }
        }
    }

    override suspend fun listFiles(
        config: SftpConnectionConfig,
        remotePath: String,
    ): Result<List<SftpEntry>> = withContext(Dispatchers.IO) {
        runCatching {
            withSftp(config) { channel ->
                @Suppress("UNCHECKED_CAST")
                val entries = channel.ls(remotePath) as Vector<ChannelSftp.LsEntry>
                entries.asSequence()
                    .filterNot { it.filename == "." || it.filename == ".." }
                    .map {
                        SftpEntry(
                            name = it.filename,
                            longName = it.longname,
                            isDirectory = it.attrs.isDir,
                        )
                    }
                    .toList()
            }
        }
    }

    override suspend fun uploadText(
        config: SftpConnectionConfig,
        remotePath: String,
        content: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            withSftp(config) { channel ->
                ByteArrayInputStream(content.toByteArray(StandardCharsets.UTF_8)).use { input ->
                    channel.put(input, remotePath)
                }
            }
        }
    }

    override suspend fun downloadText(
        config: SftpConnectionConfig,
        remotePath: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            withSftp(config) { channel ->
                channel.get(remotePath).bufferedReader(StandardCharsets.UTF_8).use { reader ->
                    reader.readText()
                }
            }
        }
    }

    private fun <T> withSftp(
        config: SftpConnectionConfig,
        block: (ChannelSftp) -> T,
    ): T {
        val session = openSession(
            host = config.host,
            port = config.port,
            username = config.username,
            password = config.password,
            privateKeyPem = config.privateKeyPem,
        )
        try {
            val channel = session.openChannel("sftp") as ChannelSftp
            try {
                channel.connect(CONNECT_TIMEOUT_MS)
                return block(channel)
            } finally {
                if (channel.isConnected) channel.disconnect()
            }
        } finally {
            if (session.isConnected) session.disconnect()
        }
    }

    private fun openSession(
        host: String,
        port: Int,
        username: String,
        password: String?,
        privateKeyPem: String?,
    ): Session {
        require(host.isNotBlank()) { "Host SSH não informado" }
        require(port in 1..65_535) { "Porta SSH inválida" }
        require(username.isNotBlank()) { "Usuário SSH não informado" }
        require(!password.isNullOrBlank() || !privateKeyPem.isNullOrBlank()) {
            "Informe senha ou chave privada"
        }

        val jsch = JSch()
        if (!privateKeyPem.isNullOrBlank()) {
            jsch.addIdentity(
                "devcontrol-memory-key",
                privateKeyPem.toByteArray(StandardCharsets.UTF_8),
                null,
                null,
            )
        }

        return jsch.getSession(username, host, port).apply {
            if (!password.isNullOrBlank()) setPassword(password)
            setConfig(
                Properties().apply {
                    put("StrictHostKeyChecking", "no")
                    put("PreferredAuthentications", "publickey,password,keyboard-interactive")
                },
            )
            connect(CONNECT_TIMEOUT_MS)
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val COMMAND_TIMEOUT_MS = 60_000L
    }
}
