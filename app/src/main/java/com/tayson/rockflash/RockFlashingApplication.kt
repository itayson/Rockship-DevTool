package com.tayson.rockflash

import android.app.Application
import com.topjohnwu.superuser.Shell

/**
 * Configuração global do processo principal e do shell root.
 *
 * O shell é criado sob demanda. Nenhuma solicitação de root é exibida apenas por
 * abrir o aplicativo; a solicitação ocorre quando o modo local é sondado/usado.
 */
class RockFlashingApplication : Application() {
    companion object {
        init {
            Shell.enableVerboseLogging = BuildConfig.DEBUG
            Shell.setDefaultBuilder(
                Shell.Builder.create()
                    .setFlags(Shell.FLAG_MOUNT_MASTER)
                    .setTimeout(15),
            )
        }
    }
}
