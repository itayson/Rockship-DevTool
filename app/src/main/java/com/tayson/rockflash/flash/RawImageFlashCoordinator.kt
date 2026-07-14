package com.tayson.rockflash.flash

import android.content.Context
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.PowerManager
import com.tayson.rockflash.safety.SafetyGate
import com.tayson.rockflash.usb.RockUsbDirectBackend
import com.tayson.rockflash.usb.RockUsbTransferPhase
import com.tayson.rockflash.usb.RockchipMode
import com.tayson.rockflash.usb.RockchipModeDetector
import com.tayson.rockflash.usb.RockchipUsbController
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class RawImageFlashResult(
    val imageSizeBytes: Long,
    val flashSizeBytes: Long,
)

/** Executa gravação integral de uma imagem de disco no LBA 0 em modo Loader. */
class RawImageFlashCoordinator(context: Context) {
    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val backend = RockUsbDirectBackend(appContext)

    suspend fun flash(
        imageUri: Uri,
        imageSizeBytes: Long,
        onProgress: (phase: RockUsbTransferPhase, completed: Long, total: Long) -> Unit,
    ): RawImageFlashResult = withContext(Dispatchers.IO) {
        require(imageSizeBytes > 0L) { "O provedor não informou um tamanho válido para a imagem" }
        require(imageSizeBytes % RockUsbDirectBackend.SECTOR_SIZE == 0L) {
            "A imagem não termina em um limite de setor de 512 bytes"
        }

        val safety = SafetyGate.snapshot(appContext)
        require(safety.writeReady) {
            "Gravação bloqueada: bateria mínima de 50% e economia de energia desativada"
        }

        val devices = usbManager.deviceList.values
            .filter { it.vendorId == RockchipUsbController.ROCKCHIP_VENDOR_ID }
        require(devices.size == 1) {
            when {
                devices.isEmpty() -> "Nenhum dispositivo Rockchip conectado"
                else -> "Mais de um dispositivo Rockchip conectado; deixe somente o alvo"
            }
        }

        val device = devices.single()
        require(usbManager.hasPermission(device)) { "Permissão USB não concedida" }

        val descriptorConnection = usbManager.openDevice(device)
            ?: throw IOException("Não foi possível abrir o dispositivo Rockchip")
        val mode = try {
            RockchipModeDetector.detect(descriptorConnection)
        } finally {
            descriptorConnection.close()
        }
        require(mode == RockchipMode.LOADER) {
            "Gravação de imagem bruta exige modo Loader; detectado: $mode"
        }

        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "RockFlashingTool:RawImageFlash",
        ).apply { setReferenceCounted(false) }

        wakeLock.acquire(MAX_WAKE_LOCK_MS)
        try {
            backend.open(device).use { session ->
                val flashInfo = session.readFlashInfo()
                require(imageSizeBytes <= flashInfo.sizeBytes) {
                    "Imagem maior que a memória: imagem=$imageSizeBytes flash=${flashInfo.sizeBytes}"
                }

                session.writeStreamAtLba(
                    openSource = {
                        appContext.contentResolver.openInputStream(imageUri)
                            ?: throw IOException("Não foi possível reabrir a imagem selecionada")
                    },
                    imageSizeBytes = imageSizeBytes,
                    startSector = 0L,
                    verify = true,
                    onProgress = onProgress,
                )

                RawImageFlashResult(
                    imageSizeBytes = imageSizeBytes,
                    flashSizeBytes = flashInfo.sizeBytes,
                )
            }
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    companion object {
        private const val MAX_WAKE_LOCK_MS = 4L * 60L * 60L * 1000L
    }
}
