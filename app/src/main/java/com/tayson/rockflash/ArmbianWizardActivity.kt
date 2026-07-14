package com.tayson.rockflash

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Compatibilidade com atalhos e versões anteriores.
 * O fluxo real foi movido para StandaloneArmbianWizardActivity.
 */
class ArmbianWizardActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, StandaloneArmbianWizardActivity::class.java))
        finish()
    }
}
