package com.tayson.rockflash.media

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
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.tayson.rockflash.R
import com.tayson.rockflash.core.TransferPhase
import com.tayson.rockflash.core.formatByteCount
import com.tayson.rockflash.media.scsi.AndroidScsiBlockDevice
import com.tayson.rockflash.media.scsi.MediaWritePhase
import com.tayson.rockflash.media.scsi.ScsiRawImageWriter
import com.tayson.rockflash.util.usbDeviceExtra
import java.io.FileInputStream
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class UsbMediaWriterService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var usbManager: UsbManager
    private var writeJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val received = intent ?: return
            when (received.action) {
                ACTION_MEDIA_USB_PERMISSION -> {
                    val device = received.usbDeviceExtra()
                    val granted = received.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    MediaWriterStore.appendLog(
                        "Permissão ${if (granted) "concedida" else "negada"}" +
                            (device?.let { " para ${it.vidPid()}" } ?: ""),
                    )
                    scanTargets()
                }

                UsbManager.ACTION_USB_DEVICE_ATTACHED,
                UsbManager.ACTION_USB_DEVICE_DETACHED,
                -> {
                    if (writeJob?.isActive == true) {
                        MediaWriterStore.appendLog("Conexão USB alterada durante a operação")
                    } else {
                        scanTargets()
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        createNotificationChannel()
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter().apply {
                addAction(ACTION_MEDIA_USB_PERMISSION)
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification("Detectando unidades USB", connectedDevice = false)
        when (intent?.action) {
            ACTION_SCAN_MEDIA -> scanTargets()
            ACTION_REQUEST_MEDIA_PERMISSION -> requestPermission(
                intent.getIntExtra(EXTRA_DEVICE_ID, INVALID_DEVICE_ID),
            )
            ACTION_WRITE_MEDIA -> startWrite(
                deviceId = intent.getIntExtra(EXTRA_DEVICE_ID, INVALID_DEVICE_ID),
                imageUri = intent.data,
                imageSizeBytes = intent.getLongExtra(EXTRA_IMAGE_SIZE, -1L),
            )
            else -> scanTargets()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun scanTargets() {
        if (writeJob?.isActive == true) return
        scope.launch {
            val targets = UsbMediaScanner.scan(usbManager).map { candidate ->
                val hasPermission = usbManager.hasPermission(candidate.device)
                if (candidate.supportLevel != MediaSupportLevel.SUPPORTED || !hasPermission) {
                    candidate.toState(permissionGranted = hasPermission)
                } else {
                    runCatching {
                        AndroidScsiBlockDevice.open(usbManager, candidate.device).use { blockDevice ->
                            candidate.toState(
                                permissionGranted = true,
                                vendor = blockDevice.inquiry.vendor,
                                product = blockDevice.inquiry.product,
                                blockSize = blockDevice.capacity.blockSize,
                                capacityBytes = blockDevice.capacity.totalBytes,
                            )
                        }
                    }.getOrElse { error ->
                        candidate.toState(
                            permissionGranted = true,
                            initializationError = error.message ?: error.javaClass.simpleName,
                        )
                    }
                }
            }
            MediaWriterStore.setTargets(targets)
            MediaWriterStore.appendLog(
                if (targets.isEmpty()) "Nenhuma unidade USB Mass Storage detectada"
                else "${targets.size} interface(s) de armazenamento USB detectada(s)",
            )
            startForegroundNotification("${targets.size} unidade(s) USB detectada(s)", connectedDevice = targets.isNotEmpty())
        }
    }

    private fun requestPermission(deviceId: Int) {
        val device = usbManager.deviceList.values.firstOrNull { it.deviceId == deviceId }
        if (device == null) {
            MediaWriterStore.appendLog("A unidade USB não está mais conectada")
            scanTargets()
            return
        }
        if (usbManager.hasPermission(device)) {
            scanTargets()
            return
        }
        val permissionIntent = Intent(ACTION_MEDIA_USB_PERMISSION).setPackage(packageName)
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            device.deviceId,
            permissionIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        usbManager.requestPermission(device, pendingIntent)
    }

    private fun startWrite(deviceId: Int, imageUri: Uri?, imageSizeBytes: Long) {
        if (writeJob?.isActive == true) {
            MediaWriterStore.appendLog("Já existe uma gravação de mídia em andamento")
            return
        }
        if (deviceId == INVALID_DEVICE_ID || imageUri == null || imageSizeBytes <= 0L) {
            MediaWriterStore.appendLog("Destino, URI ou tamanho da imagem inválido")
            return
        }
        val device = usbManager.deviceList.values.firstOrNull { it.deviceId == deviceId }
        if (device == null || !usbManager.hasPermission(device)) {
            MediaWriterStore.appendLog("Unidade desconectada ou sem autorização USB")
            scanTargets()
            return
        }

        MediaWriterStore.startOperation(imageSizeBytes, "Validando imagem e capacidade do destino")
        MediaWriterStore.appendLog("INÍCIO: gravação destrutiva em ${device.vidPid()}")
        acquireWakeLock()
        startForegroundNotification("Preparando gravação USB", connectedDevice = true)

        writeJob = scope.launch {
            try {
                val inspection = inspectImage(imageUri, imageSizeBytes)
                check(inspection.canRawWrite) { inspection.description }

                AndroidScsiBlockDevice.open(usbManager, device).use { blockDevice ->
                    check(imageSizeBytes.toULong() <= blockDevice.capacity.totalBytes) {
                        "Imagem de ${formatByteCount(imageSizeBytes)} excede o destino"
                    }
                    MediaWriterStore.appendLog(
                        "Destino: ${blockDevice.inquiry.vendor} ${blockDevice.inquiry.product} • " +
                            formatCapacity(blockDevice.capacity.totalBytes),
                    )
                    val writer = ScsiRawImageWriter(blockDevice)
                    var lastPhase: MediaWritePhase? = null
                    var lastReported = 0L
                    val threshold = maxOf(MIN_PROGRESS_BYTES, imageSizeBytes / 100L)
                    writer.writeAndVerify(
                        sourceFactory = {
                            contentResolver.openInputStream(imageUri)
                                ?: throw IOException("Não foi possível reabrir a imagem")
                        },
                        imageSizeBytes = imageSizeBytes,
                    ) { progress ->
                        val phaseChanged = progress.phase != lastPhase
                        val shouldReport = phaseChanged || progress.completedBytes == progress.totalBytes ||
                            progress.completedBytes - lastReported >= threshold
                        if (shouldReport) {
                            lastPhase = progress.phase
                            lastReported = progress.completedBytes
                            val statePhase = when (progress.phase) {
                                MediaWritePhase.WRITING -> TransferPhase.WRITING
                                MediaWritePhase.VERIFYING -> TransferPhase.VERIFYING
                            }
                            val detail = if (progress.phase == MediaWritePhase.WRITING) {
                                "Gravando blocos SCSI na unidade USB"
                            } else {
                                "Comparando a unidade com a imagem original"
                            }
                            MediaWriterStore.updateOperation(
                                statePhase,
                                progress.completedBytes,
                                progress.totalBytes,
                                detail,
                            )
                            startForegroundNotification(
                                "${statePhase.displayName}: ${formatByteCount(progress.completedBytes)} / " +
                                    formatByteCount(progress.totalBytes),
                                connectedDevice = true,
                            )
                        }
                    }
                }
                MediaWriterStore.finishOperation(imageSizeBytes, "Gravação e verificação concluídas")
                MediaWriterStore.appendLog("SUCESSO: mídia USB gravada e verificada")
                startForegroundNotification("Gravação USB concluída", connectedDevice = true)
            } catch (error: Exception) {
                val message = error.message ?: error.javaClass.simpleName
                MediaWriterStore.failOperation(imageSizeBytes, message)
                MediaWriterStore.appendLog("FALHA: $message")
                startForegroundNotification("Falha na gravação USB", connectedDevice = true)
            } finally {
                releaseWakeLock()
                writeJob = null
                scanTargets()
            }
        }
    }

    private fun inspectImage(uri: Uri, sizeBytes: Long): DiskImageInspection {
        val header = contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(HEADER_BYTES)
            val count = input.read(buffer)
            if (count <= 0) ByteArray(0) else buffer.copyOf(count)
        } ?: throw IOException("Não foi possível abrir a imagem")
        val trailer = readTrailer(uri, sizeBytes)
        val fileName = MediaWriterStore.state.value.imageName ?: uri.lastPathSegment ?: "imagem.img"
        return DiskImageInspector.inspect(fileName, sizeBytes, header, trailer)
    }

    private fun readTrailer(uri: Uri, sizeBytes: Long): ByteArray = runCatching {
        contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { stream ->
                val length = minOf(TRAILER_BYTES.toLong(), sizeBytes).toInt()
                stream.channel.position(sizeBytes - length)
                val output = ByteArray(length)
                var offset = 0
                while (offset < length) {
                    val count = stream.read(output, offset, length - offset)
                    if (count < 0) break
                    if (count == 0) continue
                    offset += count
                }
                output.copyOf(offset)
            }
        } ?: ByteArray(0)
    }.getOrElse { ByteArray(0) }

    private fun UsbMediaCandidate.toState(
        permissionGranted: Boolean,
        vendor: String? = null,
        product: String? = null,
        blockSize: Int? = null,
        capacityBytes: ULong? = null,
        initializationError: String? = null,
    ) = UsbMediaTargetState(
        deviceId = device.deviceId,
        displayName = displayName,
        vidPid = vidPid,
        transport = transport,
        supportLevel = supportLevel,
        reason = reason,
        permissionGranted = permissionGranted,
        vendor = vendor,
        product = product,
        blockSize = blockSize,
        capacityBytes = capacityBytes,
        initializationError = initializationError,
    )

    private fun startForegroundNotification(status: String, connectedDevice: Boolean) {
        val launchIntent = Intent(this, UniversalMediaWriterActivity::class.java)
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val operation = MediaWriterStore.state.value.operation
        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Gravador USB")
            .setContentText(status)
            .setContentIntent(contentIntent)
            .setOngoing(operation?.running == true)
            .setOnlyAlertOnce(true)
        if (operation?.running == true && operation.totalBytes > 0L) {
            builder.setProgress(100, (operation.fraction * 100f).toInt(), false)
        }
        val notification = builder.build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                if (connectedDevice) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                },
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Gravação de mídia USB",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "RockFlashingTool::UsbMediaWriter",
        ).apply { acquire(WAKELOCK_TIMEOUT_MS) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun UsbDevice.vidPid(): String = "%04x:%04x".format(vendorId, productId)

    private fun formatCapacity(bytes: ULong): String =
        if (bytes <= Long.MAX_VALUE.toULong()) formatByteCount(bytes.toLong()) else "$bytes bytes"

    companion object {
        private const val ACTION_SCAN_MEDIA = "com.tayson.rockflash.media.SCAN"
        private const val ACTION_REQUEST_MEDIA_PERMISSION = "com.tayson.rockflash.media.PERMISSION"
        private const val ACTION_WRITE_MEDIA = "com.tayson.rockflash.media.WRITE"
        private const val ACTION_MEDIA_USB_PERMISSION = "com.tayson.rockflash.media.USB_PERMISSION"
        private const val EXTRA_DEVICE_ID = "device_id"
        private const val EXTRA_IMAGE_SIZE = "image_size"
        private const val INVALID_DEVICE_ID = -1
        private const val NOTIFICATION_CHANNEL_ID = "usb_media_writer"
        private const val NOTIFICATION_ID = 3207
        private const val HEADER_BYTES = 64 * 1024
        private const val TRAILER_BYTES = 512
        private const val MIN_PROGRESS_BYTES = 8L * 1024L * 1024L
        private const val WAKELOCK_TIMEOUT_MS = 6L * 60L * 60L * 1000L

        fun scan(context: Context) {
            start(context, Intent(context, UsbMediaWriterService::class.java).setAction(ACTION_SCAN_MEDIA))
        }

        fun requestPermission(context: Context, deviceId: Int) {
            start(
                context,
                Intent(context, UsbMediaWriterService::class.java)
                    .setAction(ACTION_REQUEST_MEDIA_PERMISSION)
                    .putExtra(EXTRA_DEVICE_ID, deviceId),
            )
        }

        fun write(context: Context, deviceId: Int, imageUri: Uri, imageSizeBytes: Long) {
            start(
                context,
                Intent(context, UsbMediaWriterService::class.java)
                    .setAction(ACTION_WRITE_MEDIA)
                    .setData(imageUri)
                    .putExtra(EXTRA_DEVICE_ID, deviceId)
                    .putExtra(EXTRA_IMAGE_SIZE, imageSizeBytes),
            )
        }

        private fun start(context: Context, intent: Intent) {
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
