package com.example.devcontrol.sys

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.devcontrol.data.GitHubAuthConfig
import com.example.devcontrol.data.GitHubTokenBundle
import com.example.devcontrol.data.OAuthSession
import com.example.devcontrol.data.PullRequestReviewSummary
import com.example.devcontrol.data.PullRequestSummary
import com.example.devcontrol.data.RepoSummary
import com.example.devcontrol.repo.GitHubRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder

class HttpGitHubRepository(
    context: Context
) : GitHubRepository {

    private val json = Json { ignoreUnknownKeys = true }
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    private val securePrefs = EncryptedSharedPreferences.create(
        context,
        "github_secure_store",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    private val callbackPrefs = EncryptedSharedPreferences.create(
        context,
        "oauth_callback_store",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    override suspend fun createPkceSession(): OAuthSession = withContext(Dispatchers.Default) {
        val verifier = PkceUtils.randomUrlSafe(64)
        OAuthSession(
            state = PkceUtils.randomUrlSafe(32),
            codeVerifier = verifier,
            codeChallenge = PkceUtils.sha256UrlSafe(verifier)
        )
    }

    override suspend fun buildAuthorizationUrl(config: GitHubAuthConfig, session: OAuthSession): String {
        val params = mapOf(
            "client_id" to config.clientId,
            "redirect_uri" to config.redirectUri,
            "state" to session.state,
            "code_challenge" to session.codeChallenge,
            "code_challenge_method" to "S256",
            "allow_signup" to "false",
            "prompt" to "select_account"
        )
        return buildUrl("https://github.com/login/oauth/authorize", params)
    }

    override suspend fun completeAuthorization(
        config: GitHubAuthConfig,
        expectedState: String,
        codeVerifier: String
    ): Result<GitHubTokenBundle> = withContext(Dispatchers.IO) {
        runCatching {
            val code = callbackPrefs.getString("last_code", null).orEmpty()
            val state = callbackPrefs.getString("last_state", null).orEmpty()
            val error = callbackPrefs.getString("last_error", null).orEmpty()

            callbackPrefs.edit().clear().apply()

            require(error.isBlank()) { "GitHub retornou erro: $error" }
            require(code.isNotBlank()) { "Nenhum código OAuth foi encontrado" }
            require(state == expectedState) { "State inválido" }

            val body = mapOf(
                "client_id" to config.clientId,
                "client_secret" to config.clientSecret,
                "code" to code,
                "redirect_uri" to config.redirectUri,
                "code_verifier" to codeVerifier
            )

            val raw = postForm("https://github.com/login/oauth/access_token", body)
            val obj = json.parseToJsonElement(raw).jsonObject

            GitHubTokenBundle(
                accessToken = obj["access_token"]?.jsonPrimitive?.content.orEmpty(),
                refreshToken = obj["refresh_token"]?.jsonPrimitive?.contentOrNull,
                expiresInSeconds = obj["expires_in"]?.jsonPrimitive?.longOrNull,
                refreshExpiresInSeconds = obj["refresh_token_expires_in"]?.jsonPrimitive?.longOrNull,
                tokenType = obj["token_type"]?.jsonPrimitive?.content ?: "bearer",
                scope = obj["scope"]?.jsonPrimitive?.content ?: ""
            ).also {
                require(it.accessToken.isNotBlank()) { "Resposta sem access_token: $raw" }
                persistToken(it)
            }
        }
    }

    override suspend fun refreshToken(config: GitHubAuthConfig, refreshToken: String): Result<GitHubTokenBundle> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = mapOf(
                    "client_id" to config.clientId,
                    "client_secret" to config.clientSecret,
                    "grant_type" to "refresh_token",
                    "refresh_token" to refreshToken
                )
                val raw = postForm("https://github.com/login/oauth/access_token", body)
                val obj = json.parseToJsonElement(raw).jsonObject
                GitHubTokenBundle(
                    accessToken = obj["access_token"]?.jsonPrimitive?.content.orEmpty(),
                    refreshToken = obj["refresh_token"]?.jsonPrimitive?.contentOrNull,
                    expiresInSeconds = obj["expires_in"]?.jsonPrimitive?.longOrNull,
                    refreshExpiresInSeconds = obj["refresh_token_expires_in"]?.jsonPrimitive?.longOrNull,
                    tokenType = obj["token_type"]?.jsonPrimitive?.content ?: "bearer",
                    scope = obj["scope"]?.jsonPrimitive?.content ?: ""
                ).also {
                    require(it.accessToken.isNotBlank()) { "Resposta sem access_token: $raw" }
                    persistToken(it)
                }
            }
        }

    override suspend fun persistToken(bundle: GitHubTokenBundle) {
        securePrefs.edit()
            .putString("access_token", bundle.accessToken)
            .putString("refresh_token", bundle.refreshToken)
            .putLong("expires_in", bundle.expiresInSeconds ?: -1)
            .putLong("refresh_expires_in", bundle.refreshExpiresInSeconds ?: -1)
            .putString("token_type", bundle.tokenType)
            .putString("scope", bundle.scope)
            .apply()
    }

    override suspend fun readToken(): GitHubTokenBundle? {
        val access = securePrefs.getString("access_token", null) ?: return null
        return GitHubTokenBundle(
            accessToken = access,
            refreshToken = securePrefs.getString("refresh_token", null),
            expiresInSeconds = securePrefs.getLong("expires_in", -1).takeIf { it >= 0 },
            refreshExpiresInSeconds = securePrefs.getLong("refresh_expires_in", -1).takeIf { it >= 0 },
            tokenType = securePrefs.getString("token_type", "bearer") ?: "bearer",
            scope = securePrefs.getString("scope", "") ?: ""
        )
    }

    override suspend fun listRepos(token: String): Result<List<RepoSummary>> = withContext(Dispatchers.IO) {
        runCatching {
            val raw = getJson("https://api.github.com/user/repos", token)
            json.parseToJsonElement(raw).jsonArray.map { item ->
                val obj = item.jsonObject
                RepoSummary(
                    name = obj["name"]?.jsonPrimitive?.content.orEmpty(),
                    fullName = obj["full_name"]?.jsonPrimitive?.content.orEmpty(),
                    privateRepo = obj["private"]?.jsonPrimitive?.booleanOrNull ?: false,
                    defaultBranch = obj["default_branch"]?.jsonPrimitive?.content.orEmpty()
                )
            }
        }
    }

    override suspend fun listPullRequests(
        token: String,
        owner: String,
        repo: String
    ): Result<List<PullRequestSummary>> = withContext(Dispatchers.IO) {
        runCatching {
            val raw = getJson("https://api.github.com/repos/$owner/$repo/pulls?state=open", token)
            json.parseToJsonElement(raw).jsonArray.map { item ->
                val obj = item.jsonObject
                PullRequestSummary(
                    number = obj["number"]?.jsonPrimitive?.int ?: 0,
                    title = obj["title"]?.jsonPrimitive?.content.orEmpty(),
                    state = obj["state"]?.jsonPrimitive?.content.orEmpty(),
                    author = obj["user"]?.jsonObject?.get("login")?.jsonPrimitive?.content.orEmpty(),
                    headRef = obj["head"]?.jsonObject?.get("ref")?.jsonPrimitive?.content.orEmpty(),
                    baseRef = obj["base"]?.jsonObject?.get("ref")?.jsonPrimitive?.content.orEmpty(),
                    htmlUrl = obj["html_url"]?.jsonPrimitive?.content.orEmpty()
                )
            }
        }
    }

    override suspend fun listReviews(
        token: String,
        owner: String,
        repo: String,
        prNumber: Int
    ): Result<List<PullRequestReviewSummary>> = withContext(Dispatchers.IO) {
        runCatching {
            val raw = getJson("https://api.github.com/repos/$owner/$repo/pulls/$prNumber/reviews", token)
            json.parseToJsonElement(raw).jsonArray.map { item ->
                val obj = item.jsonObject
                PullRequestReviewSummary(
                    id = obj["id"]?.jsonPrimitive?.long ?: 0L,
                    user = obj["user"]?.jsonObject?.get("login")?.jsonPrimitive?.content.orEmpty(),
                    state = obj["state"]?.jsonPrimitive?.content.orEmpty(),
                    body = obj["body"]?.jsonPrimitive?.contentOrNull.orEmpty()
                )
            }
        }
    }

    override suspend fun createIssueComment(
        token: String,
        owner: String,
        repo: String,
        issueNumber: Int,
        body: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = buildJsonObject { put("body", body) }.toString()
            postJson("https://api.github.com/repos/$owner/$repo/issues/$issueNumber/comments", token, payload)
            Unit
        }
    }

    private fun buildUrl(base: String, params: Map<String, String>): String {
        val encoded = params.entries.joinToString("&") {
            "${encode(it.key)}=${encode(it.value)}"
        }
        return "$base?$encoded"
    }

    private fun getJson(url: String, token: String): String {
        val conn = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("X-GitHub-Api-Version", "2026-03-10")
        }
        val raw = readResponse(conn)
        require(conn.responseCode in 200..299) { "GitHub GET falhou ${conn.responseCode}: $raw" }
        return raw
    }

    private fun postForm(url: String, body: Map<String, String>): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        val form = body.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
        conn.outputStream.use { it.write(form.toByteArray()) }
        return readResponse(conn)
    }

    private fun postJson(url: String, token: String, jsonBody: String): String {
        val conn = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("X-GitHub-Api-Version", "2026-03-10")
        }
        conn.outputStream.use { it.write(jsonBody.toByteArray()) }
        val raw = readResponse(conn)
        require(conn.responseCode in 200..299) { "GitHub POST falhou ${conn.responseCode}: $raw" }
        return raw
    }

    private fun readResponse(conn: HttpURLConnection): String {
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        return stream.bufferedReader().use(BufferedReader::readText)
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
}
