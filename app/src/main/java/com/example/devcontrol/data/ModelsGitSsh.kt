package com.example.devcontrol.data

import kotlinx.serialization.Serializable

@Serializable
data class GitHubAuthConfig(
    val clientId: String,
    val clientSecret: String,
    val redirectUri: String = "devcontrol://oauth/callback"
)

@Serializable
data class OAuthSession(
    val state: String,
    val codeVerifier: String,
    val codeChallenge: String
)

@Serializable
data class GitHubTokenBundle(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresInSeconds: Long? = null,
    val refreshExpiresInSeconds: Long? = null,
    val tokenType: String = "bearer",
    val scope: String = ""
)

@Serializable
data class PullRequestSummary(
    val number: Int,
    val title: String,
    val state: String,
    val author: String,
    val headRef: String,
    val baseRef: String,
    val htmlUrl: String
)

@Serializable
data class PullRequestReviewSummary(
    val id: Long,
    val user: String,
    val state: String,
    val body: String
)

@Serializable
data class SshCommandRequest(
    val host: String,
    val port: Int = 22,
    val username: String,
    val password: String? = null,
    val privateKeyPem: String? = null,
    val command: String
)

@Serializable
data class SftpConnectionConfig(
    val host: String,
    val port: Int = 22,
    val username: String,
    val password: String? = null,
    val privateKeyPem: String? = null
)

@Serializable
data class SftpEntry(
    val name: String,
    val longName: String,
    val isDirectory: Boolean
)
