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
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.tayson.rockflash.R
import com.tayson.rockflash.core.ConnectionMode
import com.tayson.rockflash.core.FlashingSessionStore
import com.tayson.rockflash.ui.RockFlashingToolActivity
import com.tayson.rockflash.util.usbDeviceExtra
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Serviço foreground que mantém a detecção USB ativa durante operações longas.
 *
 * A permissão USB não é uma permissão de Manifest. Android concede acesso por
 * dispositivo através de UsbManager.requestPermission() e de um PendingIntent.
 */
class UsbHostForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var usbManager: UsbManager
    private var rootProbeCompleted = false
    private var rootAvailable = false
    private var rootHintLogged = false

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
                    if (granted) startInForeground()
                    scanConnections(device)
                }

                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = received.usbDeviceExtra()
                    FlashingSessionStore.appendLog("USB conectado${device?.let { ": ${it.vidPid()}" } ?: ""}")
                    scanConnections(device)
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    FlashingSessionStore.appendLog("USB desconectado")
                    startInForeground()
                    scanConnections()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        createNotificationChannel()
        registerUsbReceiver()
        FlashingSessionStore.appendLog("Serviço USB Host iniciado")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_PROBE_CONNECTIONS -> scanConnections(intent.usbDeviceExtra(), allowRootPrompt = true)
            else -> scanConnections(intent?.usbDeviceExtra())
        }
        return START_STICKY
    }

    override fun onDestroy() {
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

    private fun scanConnections(preferred: UsbDevice? = null, allowRootPrompt: Boolean = false) {
        serviceScope.launch {
            val rockchipDevices = usbManager.deviceList.values
                .filter { it.vendorId == RockchipUsbController.ROCKCHIP_VENDOR_ID }
                .sortedBy { it.deviceId }

            val device = preferred
                ?.takeIf { it.vendorId == RockchipUsbController.ROCKCHIP_VENDOR_ID }
                ?: rockchipDevices.firstOrNull()

            if (device == null) {
                detectLocalRootMode(allowRootPrompt)
                return@launch
            }

            val label = device.vidPid()
            if (!usbManager.hasPermission(device)) {
                FlashingSessionStore.setConnection(ConnectionMode.ROCKCHIP_UNKNOWN, label)
                FlashingSessionStore.appendLog("Rockchip $label aguardando autorização USB")
                requestUsbPermission(device)
                return@launch
            }

            startInForeground()
            val connection = usbManager.openDevice(device)
            if (connection == null) {
                FlashingSessionStore.setConnection(ConnectionMode.ROCKCHIP_UNKNOWN, label)
                FlashingSessionStore.appendLog("Falha ao abrir UsbDeviceConnection para $label")
                return@launch
            }

            val mode = try {
                RockchipModeDetector.detect(connection)
            } finally {
                connection.close()
            }

            val connectionMode = when (mode) {
                RockchipMode.MASKROM -> ConnectionMode.MASKROM
                RockchipMode.LOADER -> ConnectionMode.LOADER
                RockchipMode.UNKNOWN -> ConnectionMode.ROCKCHIP_UNKNOWN
            }
            FlashingSessionStore.setConnection(connectionMode, label)
            FlashingSessionStore.appendLog("Rockchip $label identificado como ${connectionMode.displayName}")
        }
    }

    private fun detectLocalRootMode(allowRootPrompt: Boolean) {
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

    private fun startInForeground() {
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
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(
                if (hasUsbAccess) "Conexão Rockchip USB ativa" else "Monitorando USB Host e modo local",
            )
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

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
        const val ACTION_STOP = "com.tayson.rockflash.action.STOP_USB_SERVICE"

        private const val NOTIFICATION_CHANNEL_ID = "rockchip_usb_host"
        private const val NOTIFICATION_ID = 2207
        private const val USB_PERMISSION_REQUEST_CODE = 2207

        fun start(context: Context) {
            startWithAction(context, ACTION_SCAN)
        }

        fun probeConnections(context: Context) {
            startWithAction(context, ACTION_PROBE_CONNECTIONS)
        }

        private fun startWithAction(context: Context, action: String) {
            val intent = Intent(context, UsbHostForegroundService::class.java).setAction(action)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
