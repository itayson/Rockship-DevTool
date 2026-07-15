package com.example.devcontrol

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class OAuthCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val data = intent?.data
        val validCallback = data?.scheme == "devcontrol" &&
            data.host == "oauth" &&
            data.path == "/callback"

        val code = if (validCallback) data?.getQueryParameter("code").orEmpty() else ""
        val state = if (validCallback) data?.getQueryParameter("state").orEmpty() else ""
        val error = if (validCallback) {
            data?.getQueryParameter("error").orEmpty()
        } else {
            "callback_uri_invalida"
        }

        val masterKey = MasterKey.Builder(this)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val prefs = EncryptedSharedPreferences.create(
            this,
            "oauth_callback_store",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

        prefs.edit()
            .putString("last_code", code)
            .putString("last_state", state)
            .putString("last_error", error)
            .apply()

        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
        )
        finish()
    }
}
