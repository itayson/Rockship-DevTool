package com.tayson.rockflash

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.tayson.rockflash.ui.RockFlashingToolActivity

/**
 * Compatibilidade para atalhos ou intents de versões anteriores.
 * A interface principal foi migrada para [RockFlashingToolActivity].
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, RockFlashingToolActivity::class.java).apply {
                intent?.data?.let(::setData)
                intent?.extras?.let(::putExtras)
            },
        )
        finish()
    }
}
