package com.example.devcontrol.repo

import com.example.devcontrol.data.*

interface ProfileRepository {
    suspend fun listProfiles(): List<UserProfile>
    suspend fun activeProfile(): UserProfile
    suspend fun setActiveProfile(id: String)
}

interface DeviceRepository {
    suspend fun currentSnapshot(): SystemSnapshot
    suspend fun rootAvailable(): Boolean
    suspend fun runRootCommand(command: String): CommandResult
    suspend fun capabilities(): List<DeviceCapability>
}

interface ScriptRepository {
    suspend fun runScript(name: String, script: String): CommandResult
    suspend fun build(document: EditorDocument, profile: BuildProfile): CommandResult
}

interface BackupRepository {
    suspend fun exportBackup(): String
    suspend fun importBackup(raw: String): Result<Unit>
}

interface PluginRepository {
    suspend fun listPlugins(): List<PluginDescriptor>
}

interface GitHubRepository {
    suspend fun createPkceSession(): OAuthSession
    suspend fun buildAuthorizationUrl(config: GitHubAuthConfig, session: OAuthSession): String
    suspend fun completeAuthorization(config: GitHubAuthConfig, expectedState: String, codeVerifier: String): Result<GitHubTokenBundle>
    suspend fun refreshToken(config: GitHubAuthConfig, refreshToken: String): Result<GitHubTokenBundle>
    suspend fun persistToken(bundle: GitHubTokenBundle)
    suspend fun readToken(): GitHubTokenBundle?
    suspend fun listRepos(token: String): Result<List<RepoSummary>>
    suspend fun listPullRequests(token: String, owner: String, repo: String): Result<List<PullRequestSummary>>
    suspend fun listReviews(token: String, owner: String, repo: String, prNumber: Int): Result<List<PullRequestReviewSummary>>
    suspend fun createIssueComment(token: String, owner: String, repo: String, issueNumber: Int, body: String): Result<Unit>
}

interface SshRepository {
    suspend fun exec(request: SshCommandRequest): Result<CommandResult>
    suspend fun listFiles(config: SftpConnectionConfig, remotePath: String): Result<List<SftpEntry>>
    suspend fun uploadText(config: SftpConnectionConfig, remotePath: String, content: String): Result<Unit>
    suspend fun downloadText(config: SftpConnectionConfig, remotePath: String): Result<String>
}
