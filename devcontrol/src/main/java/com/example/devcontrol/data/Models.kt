package com.example.devcontrol.data

import kotlinx.serialization.Serializable

@Serializable
data class UserProfile(
    val id: String,
    val name: String,
)

@Serializable
data class SystemSnapshot(
    val manufacturer: String,
    val model: String,
    val device: String,
    val androidVersion: String,
    val apiLevel: Int,
)

@Serializable
data class DeviceCapability(
    val id: String,
    val label: String,
    val available: Boolean,
)

@Serializable
data class CommandResult(
    val command: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val durationMs: Long,
) {
    val successful: Boolean get() = exitCode == 0
}

@Serializable
data class EditorDocument(
    val name: String,
    val content: String,
)

@Serializable
data class BuildProfile(
    val id: String,
    val name: String,
    val command: String,
)

@Serializable
data class PluginDescriptor(
    val id: String,
    val name: String,
    val description: String,
)

@Serializable
data class RepoSummary(
    val name: String,
    val fullName: String,
    val privateRepo: Boolean,
    val defaultBranch: String,
)

@Serializable
data class GitHubAuthConfig(
    val clientId: String,
    val clientSecret: String,
    val redirectUri: String = "com.example.devcontrol://oauth/callback",
    val scope: String = "repo",
)

@Serializable
data class OAuthSession(
    val state: String,
    val codeVerifier: String,
    val codeChallenge: String,
)

@Serializable
data class GitHubTokenBundle(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresInSeconds: Long? = null,
    val refreshExpiresInSeconds: Long? = null,
    val tokenType: String = "bearer",
    val scope: String = "",
)

@Serializable
data class PullRequestSummary(
    val number: Int,
    val title: String,
    val state: String,
    val author: String,
    val headRef: String,
    val baseRef: String,
    val htmlUrl: String,
)

@Serializable
data class PullRequestReviewSummary(
    val id: Long,
    val user: String,
    val state: String,
    val body: String,
)

@Serializable
data class SshCommandRequest(
    val host: String,
    val port: Int = 22,
    val username: String,
    val password: String? = null,
    val privateKeyPem: String? = null,
    val command: String,
    val timeoutMs: Long = 60_000L,
)

@Serializable
data class SftpConnectionConfig(
    val host: String,
    val port: Int = 22,
    val username: String,
    val password: String? = null,
    val privateKeyPem: String? = null,
)

@Serializable
data class SftpEntry(
    val name: String,
    val longName: String,
    val isDirectory: Boolean,
)
