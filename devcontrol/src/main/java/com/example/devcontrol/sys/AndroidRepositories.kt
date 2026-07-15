package com.example.devcontrol.sys

import android.content.Context
import android.os.Build
import com.example.devcontrol.data.BuildProfile
import com.example.devcontrol.data.CommandResult
import com.example.devcontrol.data.DeviceCapability
import com.example.devcontrol.data.EditorDocument
import com.example.devcontrol.data.PluginDescriptor
import com.example.devcontrol.data.SecurityPolicy
import com.example.devcontrol.data.SystemSnapshot
import com.example.devcontrol.data.UserProfile
import com.example.devcontrol.repo.BackupRepository
import com.example.devcontrol.repo.DeviceRepository
import com.example.devcontrol.repo.PluginRepository
import com.example.devcontrol.repo.ProfileRepository
import com.example.devcontrol.repo.ScriptRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class AndroidProfileRepository(context: Context) : ProfileRepository {
    private val prefs = context.getSharedPreferences("devcontrol_profiles", Context.MODE_PRIVATE)
    private val localProfile = UserProfile(id = "local", name = "Dispositivo local")

    override suspend fun listProfiles(): List<UserProfile> = listOf(localProfile)

    override suspend fun activeProfile(): UserProfile {
        val activeId = prefs.getString("active_profile", localProfile.id)
        return listProfiles().firstOrNull { it.id == activeId } ?: localProfile
    }

    override suspend fun setActiveProfile(id: String) {
        require(listProfiles().any { it.id == id }) { "Perfil inexistente: $id" }
        prefs.edit().putString("active_profile", id).apply()
    }
}

class AndroidDeviceRepository(
    private val context: Context,
) : DeviceRepository {
    override suspend fun currentSnapshot(): SystemSnapshot = SystemSnapshot(
        manufacturer = Build.MANUFACTURER.orEmpty(),
        model = Build.MODEL.orEmpty(),
        device = Build.DEVICE.orEmpty(),
        androidVersion = Build.VERSION.RELEASE.orEmpty(),
        apiLevel = Build.VERSION.SDK_INT,
    )

    override suspend fun rootAvailable(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            executeProcess(
                command = listOf("su", "-c", "id -u"),
                displayCommand = "su -c id -u",
                timeoutSeconds = 8,
            ).let { it.exitCode == 0 && it.stdout.trim() == "0" }
        }.getOrDefault(false)
    }

    override suspend fun runRootCommand(command: String): CommandResult = withContext(Dispatchers.IO) {
        executeProcess(
            command = listOf("su", "-c", command),
            displayCommand = command,
            timeoutSeconds = 60,
        )
    }

    override suspend fun capabilities(): List<DeviceCapability> = listOf(
        DeviceCapability("root", "Acesso root", rootAvailable()),
        DeviceCapability("github", "Cliente GitHub", true),
        DeviceCapability("ssh", "Cliente SSH/SFTP", true),
        DeviceCapability("storage", "Armazenamento interno", context.filesDir.canWrite()),
    )
}

class LocalScriptRepository(
    private val context: Context,
    private val securityPolicy: SecurityPolicy,
    private val profileRepository: ProfileRepository,
) : ScriptRepository {
    override suspend fun runScript(name: String, script: String): CommandResult = withContext(Dispatchers.IO) {
        val command = securityPolicy.validateCommand(script)
        executeProcess(
            command = listOf("sh", "-c", command),
            displayCommand = name.ifBlank { command },
            timeoutSeconds = 60,
        )
    }

    override suspend fun build(document: EditorDocument, profile: BuildProfile): CommandResult = withContext(Dispatchers.IO) {
        profileRepository.activeProfile()
        val workDir = File(context.filesDir, "workspace").apply { mkdirs() }
        val source = File(workDir, securityPolicy.safeFileName(document.name))
        source.writeText(document.content)

        val command = securityPolicy.validateCommand(
            profile.command.replace("{{file}}", shellQuote(source.absolutePath)),
        )
        executeProcess(
            command = listOf("sh", "-c", command),
            displayCommand = command,
            timeoutSeconds = 120,
        )
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}

class FileBackupRepository(
    context: Context,
    private val profileRepository: ProfileRepository,
    private val scriptRepository: ScriptRepository,
) : BackupRepository {
    private val prefs = context.getSharedPreferences("devcontrol_backup", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    override suspend fun exportBackup(): String {
        scriptRepository.hashCode()
        return json.encodeToString(
            BackupPayload(
                activeProfile = profileRepository.activeProfile(),
                exportedAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun importBackup(raw: String): Result<Unit> = runCatching {
        val payload = json.decodeFromString<BackupPayload>(raw)
        require(payload.version == 1) { "Versão de backup não suportada" }
        profileRepository.setActiveProfile(payload.activeProfile.id)
        prefs.edit().putString("last_import", raw).apply()
    }

    @Serializable
    private data class BackupPayload(
        val version: Int = 1,
        val activeProfile: UserProfile,
        val exportedAtEpochMs: Long,
    )
}

class AndroidPluginRepository : PluginRepository {
    override suspend fun listPlugins(): List<PluginDescriptor> = listOf(
        PluginDescriptor("terminal", "Terminal local", "Executa scripts no shell do Android."),
        PluginDescriptor("github", "GitHub", "Consulta repositórios, pull requests e revisões."),
        PluginDescriptor("ssh", "SSH/SFTP", "Executa comandos e transfere textos por SSH."),
    )
}

private fun executeProcess(
    command: List<String>,
    displayCommand: String,
    timeoutSeconds: Long,
): CommandResult {
    val started = System.currentTimeMillis()
    val process = ProcessBuilder(command).start()
    val stdout = StringBuilder()
    val stderr = StringBuilder()

    val stdoutThread = thread(name = "devcontrol-stdout", isDaemon = true) {
        process.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { stdout.appendLine(it) }
        }
    }
    val stderrThread = thread(name = "devcontrol-stderr", isDaemon = true) {
        process.errorStream.bufferedReader().useLines { lines ->
            lines.forEach { stderr.appendLine(it) }
        }
    }

    val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
    if (!completed) process.destroyForcibly()
    stdoutThread.join(2_000)
    stderrThread.join(2_000)

    return CommandResult(
        command = displayCommand,
        exitCode = if (completed) process.exitValue() else 124,
        stdout = stdout.toString(),
        stderr = if (completed) stderr.toString() else stderr.append("Tempo limite excedido").toString(),
        durationMs = System.currentTimeMillis() - started,
    )
}
