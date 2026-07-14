package com.tayson.rockflash.usb

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.tayson.rockflash.R
import com.tayson.rockflash.core.ConnectionMode
import com.tayson.rockflash.core.FlashingSessionStore
import com.tayson.rockflash.core.TransferPhase
import com.tayson.rockflash.core.formatByteCount
import com.tayson.rockflash.flash.RawImageFlashCoordinator
import com.tayson.rockflash.ui.RockFlashingToolActivity
import com.tayson.rockflash.util.usbDeviceExtra
import com.topjohnwu.superuser.Shell
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Serviço foreground que monitora USB e mantém operações de flash fora do ciclo
 * de vida da Activity.
 */
class UsbHostForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var usbManager: UsbManager
    private lateinit var rawImageFlasher: RawImageFlashCoordinator
    private var rootProbeCompleted = false
    private var rootAvailable = false
    private var rootHintLogged = false
    private var flashJob: Job? = null
    private val scanGeneration = AtomicLong(0L)

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val received = intent ?: return
            when (received.action) {
                ACTION_USB_PERMISSION -> {
                    val device = received.usbDeviceExtra()
                    val granted = received.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    FlashingSessionStore.appendLog(
                        "Permissão USB ${if (granted) "concedida" else "negada"}" +
                            (device?.let { " para ${it.vidPid()}" } ?: ""),
                    )
                    if (!granted) return
                    startInForeground()
                    scanConnections(device)
                }

                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = received.usbDeviceExtra()
                    FlashingSessionStore.appendLog("USB conectado${device?.let { ": ${it.vidPid()}" } ?: ""}")
                    scanConnections(device)
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    FlashingSessionStore.appendLog(
                        if (flashJob?.isActive == true) {
                            "USB desconectado durante a gravação; aguardando falha controlada"
                        } else {
                            "USB desconectado"
                        },
                    )
                    if (flashJob?.isActive != true) {
                        startInForeground()
                        scanConnections()
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        rawImageFlasher = RawImageFlashCoordinator(this)
        createNotificationChannel()
        registerUsbReceiver()
        FlashingSessionStore.appendLog("Serviço USB Host iniciado")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        when (intent?.action) {
            ACTION_STOP -> {
                if (flashJob?.isActive == true) {
                    FlashingSessionStore.appendLog("O serviço não pode parar durante uma gravação")
                } else {
                    stopSelf()
                }
            }

            ACTION_PROBE_CONNECTIONS -> scanConnections(intent.usbDeviceExtra(), allowRootPrompt = true)
            ACTION_FLASH_RAW_IMAGE -> startRawImageFlash(
                imageUri = intent.data,
                imageSizeBytes = intent.getLongExtra(EXTRA_IMAGE_SIZE_BYTES, -1L),
            )

            else -> scanConnections(intent?.usbDeviceExtra())
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scanGeneration.incrementAndGet()
        runCatching { unregisterReceiver(usbReceiver) }
        serviceScope.cancel()
        FlashingSessionStore.appendLog("Serviço USB Host encerrado")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerUsbReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(
            this,
            usbReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun startRawImageFlash(imageUri: Uri?, imageSizeBytes: Long) {
        if (flashJob?.isActive == true) {
            FlashingSessionStore.appendLog("Já existe uma operação de gravação em andamento")
            return
        }
        if (imageUri == null || imageSizeBytes <= 0L) {
            val message = "URI ou tamanho da imagem inválido"
            FlashingSessionStore.failOperation(imageSizeBytes.coerceAtLeast(0L), message)
            FlashingSessionStore.appendLog(message)
            return
        }

        scanGeneration.incrementAndGet()
        FlashingSessionStore.startOperation(imageSizeBytes, "Validando dispositivo, energia e capacidade")
        FlashingSessionStore.appendLog(
            "INÍCIO: gravação integral no LBA 0 (${formatByteCount(imageSizeBytes)})",
        )
        startInForeground("Preparando gravação")

        flashJob = serviceScope.launch {
            try {
                var lastPhase: RockUsbTransferPhase? = null
                var lastReportedBytes = 0L
                val reportThreshold = maxOf(MIN_PROGRESS_STEP_BYTES, imageSizeBytes / 100L)

                val result = rawImageFlasher.flash(imageUri, imageSizeBytes) { phase, completed, total ->
                    val phaseChanged = phase != lastPhase
                    val shouldReport = phaseChanged || completed == total ||
                        completed - lastReportedBytes >= reportThreshold
                    if (shouldReport) {
                        lastPhase = phase
                        lastReportedBytes = completed
                        val statePhase = when (phase) {
                            RockUsbTransferPhase.WRITING -> TransferPhase.WRITING
                            RockUsbTransferPhase.VERIFYING -> TransferPhase.VERIFYING
                        }
                        FlashingSessionStore.updateOperation(
                            phase = statePhase,
                            completedBytes = completed,
                            totalBytes = total,
                            detail = if (phase == RockUsbTransferPhase.WRITING) {
                                "Gravando blocos RockUSB"
                            } else {
                                "Comparando readback setor a setor"
                            },
                        )
                        startInForeground(
                            "${statePhase.displayName}: ${formatByteCount(completed)} / ${formatByteCount(total)}",
                        )
                    }
                }

                FlashingSessionStore.finishOperation(
                    totalBytes = result.imageSizeBytes,
                    detail = "Gravação e verificação concluídas",
                )
                FlashingSessionStore.appendLog(
                    "SUCESSO: imagem gravada e verificada. Flash detectada: " +
                        formatByteCount(result.flashSizeBytes),
                )
                startInForeground("Gravação concluída e verificada")
            } catch (error: Exception) {
                val message = error.message ?: error.javaClass.simpleName
                FlashingSessionStore.failOperation(imageSizeBytes, message)
                FlashingSessionStore.appendLog("FALHA NA GRAVAÇÃO: $message")
                startInForeground("Falha na gravação; consulte o terminal")
            } finally {
                flashJob = null
                scanConnections()
            }
        }
    }

    private fun scanConnections(preferred: UsbDevice? = null, allowRootPrompt: Boolean = false) {
        if (flashJob?.isActive == true) return
        val generation = scanGeneration.incrementAndGet()
        serviceScope.launch {
            val rockchipDevices = usbManager.deviceList.values
                .filter { it.vendorId == RockchipUsbController.ROCKCHIP_VENDOR_ID }
                .sortedBy { it.deviceId }

            val device = preferred
                ?.takeIf { candidate ->
                    candidate.vendorId == RockchipUsbController.ROCKCHIP_VENDOR_ID &&
                        rockchipDevices.any { it.deviceId == candidate.deviceId }
                }
                ?: rockchipDevices.firstOrNull()

            if (!isCurrentScan(generation)) return@launch
            if (device == null) {
                detectLocalRootMode(allowRootPrompt, generation)
                return@launch
            }

            val label = device.vidPid()
            if (!usbManager.hasPermission(device)) {
                if (!isCurrentScan(generation)) return@launch
                FlashingSessionStore.setConnection(ConnectionMode.ROCKCHIP_UNKNOWN, label)
                FlashingSessionStore.appendLog("Rockchip $label aguardando autorização USB")
                requestUsbPermission(device)
                return@launch
            }

            val connection = usbManager.openDevice(device)
            if (connection == null) {
                if (!isCurrentScan(generation)) return@launch
                FlashingSessionStore.setConnection(ConnectionMode.ROCKCHIP_UNKNOWN, label)
                FlashingSessionStore.appendLog("Falha ao abrir UsbDeviceConnection para $label")
                return@launch
            }

            val mode = try {
                RockchipModeDetector.detect(connection)
            } finally {
                connection.close()
            }

            if (!isCurrentScan(generation)) return@launch
            startInForeground()
            val connectionMode = when (mode) {
                RockchipMode.MASKROM -> ConnectionMode.MASKROM
                RockchipMode.LOADER -> ConnectionMode.LOADER
                RockchipMode.UNKNOWN -> ConnectionMode.ROCKCHIP_UNKNOWN
            }
            FlashingSessionStore.setConnection(connectionMode, label)
            FlashingSessionStore.appendLog("Rockchip $label identificado como ${connectionMode.displayName}")
        }
    }

    private fun detectLocalRootMode(allowRootPrompt: Boolean, generation: Long) {
        Shell.isAppGrantedRoot()?.let { granted ->
            rootProbeCompleted = true
            rootAvailable = granted
        }

        if (!rootProbeCompleted && allowRootPrompt) {
            rootAvailable = runCatching {
                val result = Shell.cmd("id -u").exec()
                result.isSuccess && result.out.firstOrNull()?.trim() == "0"
            }.getOrElse { error ->
                FlashingSessionStore.appendLog("Sondagem root falhou: ${error.message ?: error.javaClass.simpleName}")
                false
            }
            rootProbeCompleted = true
        }

        if (!isCurrentScan(generation)) return
        if (rootAvailable) {
            FlashingSessionStore.setConnection(ConnectionMode.LOCAL_ROOT, "blocos internos")
            FlashingSessionStore.appendLog("Acesso root local disponível")
        } else {
            FlashingSessionStore.setConnection(ConnectionMode.NONE)
            if (!rootProbeCompleted && !rootHintLogged) {
                FlashingSessionStore.appendLog("Toque em Detectar para verificar o modo Local Root")
                rootHintLogged = true
            }
        }
    }

    private fun isCurrentScan(generation: Long): Boolean =
        generation == scanGeneration.get() && flashJob?.isActive != true

    private fun requestUsbPermission(device: UsbDevice) {
        val permissionIntent = Intent(ACTION_USB_PERMISSION).setPackage(packageName)
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            USB_PERMISSION_REQUEST_CODE,
            permissionIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        usbManager.requestPermission(device, pendingIntent)
    }

    private fun startInForeground(statusOverride: String? = null) {
        val hasUsbAccess = usbManager.deviceList.values.any { device ->
            device.vendorId == RockchipUsbController.ROCKCHIP_VENDOR_ID && usbManager.hasPermission(device)
        }
        val launchIntent = Intent(this, RockFlashingToolActivity::class.java)
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val operation = FlashingSessionStore.state.value.operation
        val notificationBuilder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(
                statusOverride ?: if (hasUsbAccess) {
                    "Conexão Rockchip USB ativa"
                } else {
                    "Monitorando USB Host e modo local"
                },
            )
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        if (operation?.running == true && operation.totalBytes > 0L) {
            notificationBuilder.setProgress(100, (operation.fraction * 100f).toInt(), false)
        } else {
            notificationBuilder.setProgress(0, 0, false)
        }
        val notification = notificationBuilder.build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val foregroundType = if (hasUsbAccess) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
            startForeground(NOTIFICATION_ID, notification, foregroundType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Conexão Rockchip",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun UsbDevice.vidPid(): String = "%04x:%04x".format(vendorId, productId)

    companion object {
        const val ACTION_USB_PERMISSION = "com.tayson.rockflash.USB_PERMISSION"
        const val ACTION_SCAN = "com.tayson.rockflash.action.SCAN_USB"
        const val ACTION_PROBE_CONNECTIONS = "com.tayson.rockflash.action.PROBE_CONNECTIONS"
        const val ACTION_FLASH_RAW_IMAGE = "com.tayson.rockflash.action.FLASH_RAW_IMAGE"
        const val ACTION_STOP = "com.tayson.rockflash.action.STOP_USB_SERVICE"

        private const val EXTRA_IMAGE_SIZE_BYTES = "image_size_bytes"
        private const val NOTIFICATION_CHANNEL_ID = "rockchip_usb_host"
        private const val NOTIFICATION_ID = 2207
        private const val USB_PERMISSION_REQUEST_CODE = 2207
        private const val MIN_PROGRESS_STEP_BYTES = 4L * 1024L * 1024L

        fun start(context: Context) {
            startWithAction(context, ACTION_SCAN)
        }

        fun probeConnections(context: Context) {
            startWithAction(context, ACTION_PROBE_CONNECTIONS)
        }

        fun flashRawImage(context: Context, imageUri: Uri, imageSizeBytes: Long) {
            val intent = Intent(context, UsbHostForegroundService::class.java)
                .setAction(ACTION_FLASH_RAW_IMAGE)
                .setData(imageUri)
                .putExtra(EXTRA_IMAGE_SIZE_BYTES, imageSizeBytes)
            ContextCompat.startForegroundService(context, intent)
        }

        private fun startWithAction(context: Context, action: String) {
            val intent = Intent(context, UsbHostForegroundService::class.java).setAction(action)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
