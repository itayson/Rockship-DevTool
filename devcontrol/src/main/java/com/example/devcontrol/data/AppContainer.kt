package com.example.devcontrol.data

import android.content.Context
import com.example.devcontrol.repo.BackupRepository
import com.example.devcontrol.repo.DeviceRepository
import com.example.devcontrol.repo.GitHubRepository
import com.example.devcontrol.repo.PluginRepository
import com.example.devcontrol.repo.ProfileRepository
import com.example.devcontrol.repo.ScriptRepository
import com.example.devcontrol.repo.SshRepository
import com.example.devcontrol.sys.AndroidDeviceRepository
import com.example.devcontrol.sys.AndroidPluginRepository
import com.example.devcontrol.sys.AndroidProfileRepository
import com.example.devcontrol.sys.FileBackupRepository
import com.example.devcontrol.sys.HttpGitHubRepository
import com.example.devcontrol.sys.JschSshRepository
import com.example.devcontrol.sys.LocalScriptRepository

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val securityPolicy = SecurityPolicy()
    val profileRepository: ProfileRepository = AndroidProfileRepository(appContext)
    val deviceRepository: DeviceRepository = AndroidDeviceRepository(appContext)
    val scriptRepository: ScriptRepository = LocalScriptRepository(
        appContext,
        securityPolicy,
        profileRepository,
    )
    val backupRepository: BackupRepository = FileBackupRepository(
        appContext,
        profileRepository,
        scriptRepository,
    )
    val pluginRepository: PluginRepository = AndroidPluginRepository()
    val gitHubRepository: GitHubRepository = HttpGitHubRepository(appContext)
    val sshRepository: SshRepository = JschSshRepository()
}
