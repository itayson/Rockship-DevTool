package com.tayson.rockflash

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.tayson.rockflash.databinding.ActivityMainBinding
import com.tayson.rockflash.nativebridge.NativeBridge
import com.tayson.rockflash.report.DeviceReport
import com.tayson.rockflash.root.RkDevelopToolBackend
import com.tayson.rockflash.safety.SafetyGate
import com.tayson.rockflash.safety.SafetySnapshot
import com.tayson.rockflash.usb.RockchipUsbController
import com.tayson.rockflash.usb.RockchipUsbDevice
import com.tayson.rockflash.util.usbDeviceExtra
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var usbController: RockchipUsbController
    private val rootBackend = RkDevelopToolBackend()

    private var selectedDevice: RockchipUsbDevice? = null
    private var openConnection: UsbDeviceConnection? = null
    private var safetySnapshot: SafetySnapshot? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val receivedIntent = intent ?: return
            when (receivedIntent.action) {
                ACTION_USB_PERMISSION -> {
                    val granted = receivedIntent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    appendLog("Permissão USB: ${if (granted) "concedida" else "negada"}")
                    refreshDevices(receivedIntent.usbDeviceExtra())
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    appendLog("Dispositivo USB conectado")
                    refreshDevices(receivedIntent.usbDeviceExtra())
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    appendLog("Dispositivo USB desconectado")
                    closeNativeSession()
                    refreshDevices()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        usbController = RockchipUsbController(this)
        registerUsbReceiver()
        configureActions()
        appendLog("Aplicativo iniciado")
        appendLog("Backend: ${NativeBridge.backendVersion()}")
        refreshSafety()
        refreshDevices(intent.usbDeviceExtra())
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        refreshDevices(intent?.usbDeviceExtra())
    }

    override fun onResume() {
        super.onResume()
        refreshSafety()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(usbReceiver) }
        closeNativeSession()
        super.onDestroy()
    }

    private fun configureActions() = with(binding) {
        scanButton.setOnClickListener { refreshDevices() }
        permissionButton.setOnClickListener {
            selectedDevice?.let { usbController.requestPermission(it.device, ACTION_USB_PERMISSION) }
        }
        openNativeButton.setOnClickListener { openNativeSession() }
        listButton.setOnClickListener { runReadOnly(RkDevelopToolBackend.ReadOnlyCommand.LIST) }
        chipButton.setOnClickListener { runReadOnly(RkDevelopToolBackend.ReadOnlyCommand.READ_CHIP_INFO) }
        flashIdButton.setOnClickListener { runReadOnly(RkDevelopToolBackend.ReadOnlyCommand.READ_FLASH_ID) }
        flashInfoButton.setOnClickListener { runReadOnly(RkDevelopToolBackend.ReadOnlyCommand.READ_FLASH_INFO) }
        capabilityButton.setOnClickListener { runReadOnly(RkDevelopToolBackend.ReadOnlyCommand.READ_CAPABILITY) }
        clearLogButton.setOnClickListener { logText.text = "" }
        copyReportButton.setOnClickListener { copyReport() }
    }

    private fun refreshDevices(preferred: UsbDevice? = null) {
        val devices = usbController.findDevices()
        selectedDevice = preferred?.let { preferredDevice ->
            devices.firstOrNull { it.device.deviceId == preferredDevice.deviceId }
        } ?: devices.firstOrNull()

        val device = selectedDevice
        if (device == null) {
            binding.statusText.text = getString(R.string.status_no_device)
            binding.statusText.setTextColor(getColor(R.color.rock_warning))
            binding.deviceText.text = "Conecte o aparelho em Loader ou MaskROM usando USB OTG.\nVID esperado: 2207"
        } else {
            val permission = usbController.hasPermission(device.device)
            binding.statusText.text = getString(R.string.status_detected)
            binding.statusText.setTextColor(getColor(R.color.rock_secondary))
            binding.deviceText.text = buildString {
                appendLine("VID:PID: ${device.vidPid}")
                appendLine("Modo inferido: ${device.mode}")
                appendLine("Node: ${device.deviceName}")
                appendLine("Permissão Android: ${if (permission) "concedida" else "pendente"}")
                appendLine("Interfaces: ${device.device.interfaceCount}")
                append(usbController.describeInterfaces(device.device))
            }
            appendLog("Detectado ${device.vidPid} em ${device.deviceName}")
        }
        updateButtons()
    }

    private fun refreshSafety() {
        safetySnapshot = SafetyGate.snapshot(this)
        binding.safetyText.text = safetySnapshot?.summary().orEmpty()
    }

    private fun openNativeSession() {
        val device = selectedDevice ?: return
        if (!usbController.hasPermission(device.device)) {
            showMessage("Solicite permissão USB primeiro")
            return
        }

        closeNativeSession()
        openConnection = usbController.open(device.device)
        val connection = openConnection
        if (connection == null) {
            appendLog("Falha ao abrir UsbDeviceConnection")
            return
        }

        val opened = NativeBridge.openUsbFileDescriptor(connection.fileDescriptor)
        appendLog("Sessão nativa: ${if (opened) "aberta" else "falhou"}")
        if (opened) appendLog(NativeBridge.describeOpenDescriptor())
    }

    private fun closeNativeSession() {
        NativeBridge.closeUsbFileDescriptor()
        openConnection?.close()
        openConnection = null
    }

    private fun runReadOnly(command: RkDevelopToolBackend.ReadOnlyCommand) {
        val device = selectedDevice
        if (device == null) {
            showMessage("Conecte um dispositivo Rockchip primeiro")
            return
        }
        binding.progressBar.visibility = View.VISIBLE
        setCommandButtonsEnabled(false)
        appendLog("Executando ${command.name}…")
        lifecycleScope.launch {
            val result = rootBackend.runReadOnly(command, device.deviceName)
            binding.progressBar.visibility = View.GONE
            setCommandButtonsEnabled(true)
            appendLog(
                buildString {
                    appendLine("${command.name}: exit=${result.exitCode} tempo=${result.durationMs}ms")
                    if (result.output.isNotBlank()) append(result.output)
                }.trim(),
            )
            if (!result.success) showMessage("Comando não concluído")
        }
    }

    private fun copyReport() {
        val safety = safetySnapshot ?: SafetyGate.snapshot(this)
        val report = DeviceReport.create(
            device = selectedDevice,
            safety = safety,
            backendVersion = NativeBridge.backendVersion(),
            log = binding.logText.text?.toString().orEmpty(),
        )
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("RockFlash report", report))
        showMessage("Relatório copiado")
    }

    private fun updateButtons() {
        val device = selectedDevice
        val permission = device != null && usbController.hasPermission(device.device)
        binding.permissionButton.isEnabled = device != null && !permission
        binding.openNativeButton.isEnabled = permission
        setCommandButtonsEnabled(device != null)
    }

    private fun setCommandButtonsEnabled(enabled: Boolean) = with(binding) {
        listButton.isEnabled = enabled
        chipButton.isEnabled = enabled
        flashIdButton.isEnabled = enabled
        flashInfoButton.isEnabled = enabled
        capabilityButton.isEnabled = enabled
    }

    private fun appendLog(message: String) {
        val current = binding.logText.text?.toString().orEmpty()
        binding.logText.text = if (current.isBlank()) message else "$current\n$message"
    }

    private fun showMessage(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    private fun registerUsbReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(usbReceiver, filter)
        }
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.tayson.rockflash.USB_PERMISSION"
    }
}
