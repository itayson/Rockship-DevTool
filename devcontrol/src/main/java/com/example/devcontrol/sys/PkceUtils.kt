package com.example.devcontrol.sys

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

object PkceUtils {
    fun randomUrlSafe(length: Int = 64): String {
        require(length in 32..96) { "O tamanho PKCE deve estar entre 32 e 96 bytes" }
        val bytes = ByteArray(length)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
    }

    fun sha256UrlSafe(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(
            digest,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
    }
}
