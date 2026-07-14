package com.tayson.rockflash

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.tayson.rockflash.databinding.ActivityMainBinding
import com.tayson.rockflash.nativebridge.NativeBridge
import com.tayson.rockflash.report.DeviceReport
import com.tayson.rockflash.root.RkBackendStatus
import com.tayson.rockflash.root.RkDevelopToolBackend
import com.tayson.rockflash.safety.SafetyGate
import com.tayson.rockflash.safety.SafetySnapshot
import com.tayson.rockflash.usb.RockchipUsbController
import com.tayson.rockflash.usb.RockchipUsbDevice
import com.tayson.rockflash.util.usbDeviceExtra
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tukaani.xz.XZInputStream

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var usbController: RockchipUsbController
    private val rootBackend = RkDevelopToolBackend()

    private var selectedDevice: RockchipUsbDevice? = null
    private var openConnection: UsbDeviceConnection? = null
    private var safetySnapshot: SafetySnapshot? = null
    private var backendStatus: RkBackendStatus? = null
    private var backendRefreshJob: Job? = null
    private var backendRefreshGeneration = 0L
    private var stagedFile: File? = null
    private var busy = false

    private val imagePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) stageSelectedImage(uri)
    }

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
        appendLog("Backend nativo: ${NativeBridge.backendVersion()}")
        refreshSafety()
        refreshDevices(intent.usbDeviceExtra())
        refreshBackendStatus(showFeedback = false)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        refreshDevices(intent?.usbDeviceExtra())
    }

    override fun onResume() {
        super.onResume()
        refreshSafety()
        if (!busy) refreshBackendStatus(showFeedback = false)
    }

    override fun onDestroy() {
        backendRefreshGeneration++
        backendRefreshJob?.cancel()
        runCatching { unregisterReceiver(usbReceiver) }
        closeNativeSession()
        super.onDestroy()
    }

    private fun configureActions() = with(binding) {
        scanButton.setOnClickListener {
            refreshDevices()
            refreshBackendStatus(showFeedback = false)
        }
        permissionButton.setOnClickListener {
            selectedDevice?.let { usbController.requestPermission(it.device, ACTION_USB_PERMISSION) }
        }
        openNativeButton.setOnClickListener { openNativeSession() }
        armbianWizardButton.setOnClickListener {
            startActivity(Intent(this@MainActivity, ArmbianWizardActivity::class.java))
        }
        listButton.setOnClickListener { runReadOnly(RkDevelopToolBackend.ReadOnlyCommand.LIST) }
        chipButton.setOnClickListener { runReadOnly(RkDevelopToolBackend.ReadOnlyCommand.READ_CHIP_INFO) }
        flashIdButton.setOnClickListener { runReadOnly(RkDevelopToolBackend.ReadOnlyCommand.READ_FLASH_ID) }
        flashInfoButton.setOnClickListener { runReadOnly(RkDevelopToolBackend.ReadOnlyCommand.READ_FLASH_INFO) }
        capabilityButton.setOnClickListener { runReadOnly(RkDevelopToolBackend.ReadOnlyCommand.READ_CAPABILITY) }
        partitionsButton.setOnClickListener { runReadOnly(RkDevelopToolBackend.ReadOnlyCommand.PRINT_PARTITIONS) }

        selectImageButton.setOnClickListener { imagePicker.launch(arrayOf("*/*")) }
        flashRawButton.setOnClickListener { confirmAndRun(RkDevelopToolBackend.WriteCommand.WRITE_RAW_LBA) }
        flashPartitionButton.setOnClickListener { confirmAndRun(RkDevelopToolBackend.WriteCommand.WRITE_PARTITION) }
        downloadLoaderButton.setOnClickListener { confirmAndRun(RkDevelopToolBackend.WriteCommand.DOWNLOAD_BOOT) }
        upgradeLoaderButton.setOnClickListener { confirmAndRun(RkDevelopToolBackend.WriteCommand.UPGRADE_LOADER) }
        writeGptButton.setOnClickListener { confirmAndRun(RkDevelopToolBackend.WriteCommand.WRITE_GPT) }
        writeParameterButton.setOnClickListener { confirmAndRun(RkDevelopToolBackend.WriteCommand.WRITE_PARAMETER) }
        eraseFlashButton.setOnClickListener { confirmAndRun(RkDevelopToolBackend.WriteCommand.ERASE_FLASH) }
        resetDeviceButton.setOnClickListener { confirmAndRun(RkDevelopToolBackend.WriteCommand.RESET_DEVICE) }

        clearLogButton.setOnClickListener { logText.text = "" }
        copyReportButton.setOnClickListener { copyReport() }
    }

    private fun refreshBackendStatus(showFeedback: Boolean) {
        val generation = ++backendRefreshGeneration
        backendRefreshJob?.cancel()
        backendRefreshJob = lifecycleScope.launch {
            val status = rootBackend.status(force = showFeedback)
            if (generation != backendRefreshGeneration) return@launch

            val changed = status != backendStatus
            backendStatus = status
            if (changed || showFeedback) appendLog("AMBIENTE: ${status.diagnostic}")
            updateEnvironmentText()
            updateButtons()
            if (showFeedback) {
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("Diagnóstico do backend")
                    .setMessage(status.diagnostic)
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
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
        updateEnvironmentText()
    }

    private fun updateEnvironmentText() {
        binding.safetyText.text = buildString {
            append(safetySnapshot?.summary().orEmpty())
            appendLine()
            append(backendStatus?.summary ?: "Backend: verificando root e rkdeveloptool…")
        }.trim()
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

    private fun requireBackendReady(): Boolean {
        val status = backendStatus
        if (status?.ready == true) return true
        showMessage(status?.summary ?: "O diagnóstico do backend ainda não terminou")
        refreshBackendStatus(showFeedback = true)
        return false
    }

    private fun runReadOnly(command: RkDevelopToolBackend.ReadOnlyCommand) {
        val device = selectedDevice
        if (device == null) {
            showMessage("Conecte um dispositivo Rockchip primeiro")
            return
        }
        if (!requireBackendReady()) return

        setBusy(true)
        appendLog("Executando ${command.name}…")
        lifecycleScope.launch {
            val result = rootBackend.runReadOnly(command, device.deviceName)
            setBusy(false)
            appendResult(command.name, result.exitCode, result.durationMs, result.output)
            if (!result.success) showMessage("Comando não concluído")
        }
    }

    private fun stageSelectedImage(uri: Uri) {
        lifecycleScope.launch {
            setBusy(true)
            appendLog("Preparando arquivo selecionado…")
            val result = runCatching {
                withContext(Dispatchers.IO) { copyUriToStaging(uri) }
            }
            setBusy(false)
            result.onSuccess { file ->
                stagedFile = file
                binding.selectedImageText.text = "${file.name}\n${file.absolutePath}\n${formatBytes(file.length())}"
                appendLog("Arquivo preparado: ${file.name} (${formatBytes(file.length())})")
                updateButtons()
            }.onFailure { error ->
                appendLog("Falha ao preparar arquivo: ${error.message}")
                showMessage(error.message ?: "Falha ao preparar arquivo")
            }
        }
    }

    private fun copyUriToStaging(uri: Uri): File {
        val originalName = queryDisplayName(uri) ?: "imagem-${System.currentTimeMillis()}.img"
        val safeName = originalName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val compressed = safeName.lowercase().endsWith(".xz")
        val outputName = if (compressed) safeName.dropLast(3) else safeName
        val directory = File(getExternalFilesDir(null) ?: filesDir, "staging").apply { mkdirs() }
        val target = File(directory, outputName)
        if (target.exists() && !target.delete()) error("Não foi possível substituir ${target.name}")

        val declaredSize = contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        if (declaredSize > 0 && directory.usableSpace < declaredSize + MIN_FREE_MARGIN) {
            error("Espaço insuficiente para preparar a imagem")
        }

        val source = contentResolver.openInputStream(uri) ?: error("Não foi possível abrir o arquivo")
        val input = if (compressed) XZInputStream(source) else source
        input.use { stream ->
            target.outputStream().buffered(BUFFER_SIZE).use { output ->
                stream.copyTo(output, BUFFER_SIZE)
            }
        }
        require(target.length() > 0) { "O arquivo preparado ficou vazio" }
        return target
    }

    private fun queryDisplayName(uri: Uri): String? {
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor != null && cursor.moveToFirst()) cursor.getString(0) else null
        } finally {
            cursor?.close()
        }
    }

    private fun confirmAndRun(command: RkDevelopToolBackend.WriteCommand) {
        val device = selectedDevice
        if (device == null) {
            showMessage("Conecte um dispositivo Rockchip primeiro")
            return
        }
        if (!requireBackendReady()) return

        refreshSafety()
        val safety = safetySnapshot ?: return
        if (!safety.writeReady && command != RkDevelopToolBackend.WriteCommand.RESET_DEVICE) {
            showMessage("Gravação bloqueada: conecte o carregador, use bateria acima de 50% e desative a economia de energia")
            return
        }

        val file = stagedFile
        if (command.requiresFile && (file == null || !file.isFile)) {
            showMessage("Selecione e prepare uma imagem primeiro")
            return
        }

        val startSector = binding.startSectorInput.text?.toString()?.trim()?.toLongOrNull()
        val partition = binding.partitionInput.text?.toString()?.trim()
        val confirmationWord = if (command == RkDevelopToolBackend.WriteCommand.ERASE_FLASH) "APAGAR" else "FLASH"
        val input = EditText(this).apply {
            hint = confirmationWord
            isSingleLine = true
        }
        val message = buildString {
            appendLine(operationName(command))
            if (file != null && command.requiresFile) appendLine("Arquivo: ${file.name} (${formatBytes(file.length())})")
            if (command == RkDevelopToolBackend.WriteCommand.WRITE_RAW_LBA) appendLine("Setor inicial: ${startSector ?: "inválido"}")
            if (command == RkDevelopToolBackend.WriteCommand.WRITE_PARTITION) appendLine("Partição: ${partition.orEmpty()}")
            appendLine()
            append("Digite $confirmationWord para confirmar. Interromper energia ou USB durante a operação pode inutilizar a TV Box.")
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Confirmar operação")
            .setMessage(message)
            .setView(input)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Executar", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val typed = input.text?.toString()?.trim().orEmpty()
                if (typed != confirmationWord) {
                    input.error = "Digite $confirmationWord exatamente"
                } else {
                    dialog.dismiss()
                    runWrite(command, file, partition, startSector, typed)
                }
            }
        }
        dialog.show()
    }

    private fun runWrite(
        command: RkDevelopToolBackend.WriteCommand,
        file: File?,
        partition: String?,
        startSector: Long?,
        confirmation: String,
    ) {
        val device = selectedDevice ?: return
        setBusy(true)
        appendLog("INÍCIO: ${operationName(command)}")
        lifecycleScope.launch {
            val result = rootBackend.runWrite(
                command = command,
                usbDeviceNode = device.deviceName,
                filePath = file?.absolutePath,
                partitionName = partition,
                startSector = startSector,
                confirmation = confirmation,
            )
            setBusy(false)
            appendResult(command.name, result.exitCode, result.durationMs, result.output)
            showMessage(if (result.success) "Operação concluída" else "Operação falhou; consulte o log")
        }
    }

    private fun operationName(command: RkDevelopToolBackend.WriteCommand): String = when (command) {
        RkDevelopToolBackend.WriteCommand.DOWNLOAD_BOOT -> "Carregar loader temporário"
        RkDevelopToolBackend.WriteCommand.UPGRADE_LOADER -> "Atualizar loader persistente"
        RkDevelopToolBackend.WriteCommand.WRITE_RAW_LBA -> "Gravar imagem bruta completa"
        RkDevelopToolBackend.WriteCommand.WRITE_PARTITION -> "Gravar imagem em partição"
        RkDevelopToolBackend.WriteCommand.WRITE_GPT -> "Gravar tabela GPT"
        RkDevelopToolBackend.WriteCommand.WRITE_PARAMETER -> "Gravar parameter Rockchip"
        RkDevelopToolBackend.WriteCommand.ERASE_FLASH -> "Apagar toda a memória flash"
        RkDevelopToolBackend.WriteCommand.RESET_DEVICE -> "Reiniciar dispositivo"
    }

    private fun appendResult(name: String, exitCode: Int, durationMs: Long, output: String) {
        appendLog(
            buildString {
                appendLine("$name: exit=$exitCode tempo=${durationMs}ms")
                if (output.isNotBlank()) append(output)
            }.trim(),
        )
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

    private fun setBusy(value: Boolean) {
        busy = value
        binding.progressBar.visibility = if (value) View.VISIBLE else View.GONE
        updateButtons()
    }

    private fun updateButtons() {
        val device = selectedDevice
        val permission = device != null && usbController.hasPermission(device.device)
        val deviceEnabled = device != null && !busy
        val backendReady = backendStatus?.ready == true
        binding.scanButton.isEnabled = !busy
        binding.permissionButton.isEnabled = device != null && !permission && !busy
        binding.openNativeButton.isEnabled = permission && !busy
        binding.armbianWizardButton.isEnabled = !busy
        binding.selectImageButton.isEnabled = !busy
        setCommandButtonsEnabled(deviceEnabled && backendReady)
        setWriteButtonsEnabled(deviceEnabled && backendReady)
    }

    private fun setCommandButtonsEnabled(enabled: Boolean) = with(binding) {
        listButton.isEnabled = enabled
        chipButton.isEnabled = enabled
        flashIdButton.isEnabled = enabled
        flashInfoButton.isEnabled = enabled
        capabilityButton.isEnabled = enabled
        partitionsButton.isEnabled = enabled
    }

    private fun setWriteButtonsEnabled(enabled: Boolean) = with(binding) {
        val fileReady = stagedFile?.isFile == true
        flashRawButton.isEnabled = enabled && fileReady
        flashPartitionButton.isEnabled = enabled && fileReady
        downloadLoaderButton.isEnabled = enabled && fileReady
        upgradeLoaderButton.isEnabled = enabled && fileReady
        writeGptButton.isEnabled = enabled && fileReady
        writeParameterButton.isEnabled = enabled && fileReady
        eraseFlashButton.isEnabled = enabled
        resetDeviceButton.isEnabled = enabled
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
        ContextCompat.registerReceiver(
            this,
            usbReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
        else -> "%.2f KB".format(bytes / 1024.0)
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.tayson.rockflash.USB_PERMISSION"
        private const val BUFFER_SIZE = 1024 * 1024
        private const val MIN_FREE_MARGIN = 512L * 1024L * 1024L
    }
}
