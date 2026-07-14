package com.tayson.rockflash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.tayson.rockflash.armbian.ArmbianImageValidator
import com.tayson.rockflash.armbian.ImageRole
import com.tayson.rockflash.databinding.ActivityArmbianWizardBinding
import com.tayson.rockflash.safety.SafetyGate
import com.tayson.rockflash.usb.RockUsbDirectBackend
import com.tayson.rockflash.usb.RockchipUsbController
import com.tayson.rockflash.usb.UsbHostInspector
import com.tayson.rockflash.usb.UsbMassStorageDirectBackend
import com.tayson.rockflash.util.usbDeviceExtra
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tukaani.xz.XZInputStream

/**
 * Assistente que usa somente APIs do próprio Android e código incluído no APK.
 * Não executa su, rkdeveloptool, dd, Termux ou qualquer aplicativo externo.
 */
class StandaloneArmbianWizardActivity : AppCompatActivity() {
    private lateinit var binding: ActivityArmbianWizardBinding
    private lateinit var usbController: RockchipUsbController
    private lateinit var usbHostInspector: UsbHostInspector
    private lateinit var rockBackend: RockUsbDirectBackend
    private lateinit var massStorageBackend: UsbMassStorageDirectBackend

    private var pendingRole: ImageRole? = null
    private var ubootFile: File? = null
    private var armbianFile: File? = null
    private var multitoolFile: File? = null
    private var rockchipDeviceName: String? = null
    private var rockchipIdentity: String? = null
    private var flashInfo: RockUsbDirectBackend.RockUsbFlashInfo? = null
    private var flashKind: FlashKind = FlashKind.UNKNOWN
    private var backupFile: File? = null
    private var selectedUsbDeviceName: String? = null
    private var selectedUsbTarget: UsbMassStorageDirectBackend.UsbMassStorageTarget? = null
    private var usbHostSummary: String? = null
    private var busy = false

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val role = pendingRole
        pendingRole = null
        if (uri != null && role != null) stageFile(uri, role)
    }

    private val imagesFolderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) copyArmbianIntoImagesFolder(uri)
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val received = intent ?: return
            when (received.action) {
                ACTION_USB_PERMISSION -> {
                    val granted = received.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val device = received.usbDeviceExtra()
                    appendLog(
                        "Permissão USB ${if (granted) "concedida" else "negada"}: " +
                            (device?.deviceName ?: "dispositivo desconhecido"),
                    )
                    refreshEnvironment(showFeedback = false)
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    appendLog("USB conectado: ${received.usbDeviceExtra()?.deviceName.orEmpty()}")
                    refreshEnvironment(showFeedback = false)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val removed = received.usbDeviceExtra()?.deviceName
                    if (removed == rockchipDeviceName) clearRockchipState("TV Box desconectada")
                    if (removed == selectedUsbDeviceName) clearUsbTarget("Pendrive desconectado")
                    refreshEnvironment(showFeedback = false)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityArmbianWizardBinding.inflate(layoutInflater)
        setContentView(binding.root)
        usbController = RockchipUsbController(this)
        usbHostInspector = UsbHostInspector(this)
        rockBackend = RockUsbDirectBackend(this)
        massStorageBackend = UsbMassStorageDirectBackend(this)
        registerUsbReceiver()
        configureActions()
        appendLog("Modo autônomo iniciado: Android USB Host + RockUSB + SCSI internos")
        refreshEnvironment(showFeedback = false)
    }

    override fun onResume() {
        super.onResume()
        if (!busy) refreshEnvironment(showFeedback = false)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(usbReceiver) }
        super.onDestroy()
    }

    private fun configureActions() = with(binding) {
        closeButton.setOnClickListener { finish() }
        detectBoxButton.setOnClickListener { detectBox() }
        forceNandButton.setOnClickListener {
            flashKind = FlashKind.NAND
            appendLog("Armazenamento confirmado manualmente como NAND")
            updateUi()
        }
        forceEmmcButton.setOnClickListener {
            flashKind = FlashKind.EMMC
            appendLog("Armazenamento confirmado manualmente como eMMC")
            updateUi()
        }
        backupButton.setOnClickListener { createFullBackup() }
        selectUbootButton.setOnClickListener { selectFile(ImageRole.UBOOT_MAIN) }
        selectArmbianButton.setOnClickListener { selectFile(ImageRole.ARMBIAN) }
        selectMultitoolButton.setOnClickListener { selectFile(ImageRole.MULTITOOL) }
        detectUsbButton.setOnClickListener { detectUsbTargets() }
        writeArmbianUsbButton.setOnClickListener { writeImageToUsb(armbianFile, "Armbian") }
        writeMultitoolUsbButton.setOnClickListener { writeImageToUsb(multitoolFile, "Multitool") }
        copyArmbianButton.setOnClickListener { imagesFolderPicker.launch(null) }
        installBootstrapButton.setOnClickListener { confirmBootstrapInstall() }
        refreshButton.setOnClickListener { refreshEnvironment(showFeedback = true) }
    }

    private fun refreshEnvironment(showFeedback: Boolean) {
        val all = usbHostInspector.allDevices()
        usbHostSummary = when {
            all.isEmpty() -> "Nenhum USB detectado"
            else -> "${all.size} dispositivo(s) USB detectado(s) pelo Android"
        }
        updateUi()
        if (showFeedback) {
            val usbText = if (all.isEmpty()) {
                "Nenhum dispositivo USB detectado."
            } else {
                all.joinToString("\n") { "• ${it.label}${if (it.massStorage) " [Mass Storage/SCSI]" else ""}" }
            }
            MaterialAlertDialogBuilder(this)
                .setTitle("Modo autônomo")
                .setMessage(
                    buildString {
                        appendLine("Backend RockUSB: incluído no APK")
                        appendLine("Backend USB Mass Storage/SCSI: incluído no APK")
                        appendLine("Root: não necessário")
                        appendLine("Termux/rkdeveloptool/dd: não utilizados")
                        appendLine()
                        appendLine("Dispositivos USB Host:")
                        append(usbText)
                        appendLine()
                        appendLine()
                        append("O Android ainda exibirá a autorização USB do sistema; isso é uma permissão do sistema, não um aplicativo externo.")
                    },
                )
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun selectFile(role: ImageRole) {
        pendingRole = role
        filePicker.launch(arrayOf("application/octet-stream", "application/x-xz", "*/*"))
    }

    private fun detectBox() {
        val device = usbController.findDevices().firstOrNull()
        if (device == null) {
            clearRockchipState("TV Box não detectada")
            showMessage("Conecte a TV Box Rockchip em Loader pelo USB OTG")
            return
        }
        if (!usbController.hasPermission(device.device)) {
            usbController.requestPermission(device.device, ACTION_USB_PERMISSION)
            showMessage("Autorize o acesso USB e toque em Detectar novamente")
            return
        }

        val identity = "${device.vidPid}:${device.device.deviceId}"
        if (identity != rockchipIdentity) {
            clearRockchipState("Novo dispositivo Rockchip; backup anterior invalidado")
            rockchipIdentity = identity
        }
        rockchipDeviceName = device.deviceName
        appendLog("Rockchip ${device.vidPid} aberto diretamente pelo Android em ${device.deviceName}")

        runBusy("Lendo chip e flash pelo backend RockUSB interno…") {
            val result = withContext(Dispatchers.IO) {
                rockBackend.open(device.device).use { session ->
                    DirectProbe(
                        chip = session.readChipInfo(),
                        flashId = session.readFlashId(),
                        capability = session.readCapability(),
                        storage = runCatching { session.readStorage() }.getOrDefault(255),
                        flash = session.readFlashInfo(),
                    )
                }
            }
            flashInfo = result.flash
            appendLog("Chip: ${result.chip.toHex()}")
            appendLog("Flash ID: ${result.flashId.toHex()}")
            appendLog("Capacidade: ${result.capability.toHex()}")
            appendLog("Storage code: ${result.storage}")
            appendLog(result.flash.summary)
            showMessage("TV Box detectada sem root: ${result.flash.sizeMb} MB")
        }
    }

    private fun createFullBackup() {
        val device = currentRockchipDevice() ?: return
        val flash = flashInfo
        if (flash == null || flash.sizeSectors <= 0L) {
            showMessage("Detecte a TV Box e leia o tamanho da flash primeiro")
            return
        }
        if (!SafetyGate.snapshot(this).writeReady) {
            showMessage("Conecte o carregador, mantenha bateria acima de 50% e desative economia de energia")
            return
        }
        val directory = File(getExternalFilesDir("backups") ?: filesDir, "rockflash").apply { mkdirs() }
        val output = File(directory, "rk322x-${flashKind.name.lowercase()}-${System.currentTimeMillis()}.img")

        runBusy("Criando backup completo pelo RockUSB interno…") {
            var lastPercent = -1
            withContext(Dispatchers.IO) {
                rockBackend.open(device).use { session ->
                    session.backupToFile(output, flash.sizeSectors) { completed, total ->
                        val percent = ((completed * 100) / total).toInt()
                        if (percent >= lastPercent + 5 || percent == 100) {
                            lastPercent = percent
                            runOnUiThread { appendLog("Backup: $percent%") }
                        }
                    }
                }
            }
            val digest = withContext(Dispatchers.IO) { sha256(output) }
            File(output.parentFile ?: directory, "${output.name}.sha256").writeText("$digest  ${output.name}\n")
            backupFile = output
            appendLog("SHA-256: $digest")
            showMessage("Backup autônomo concluído e verificado por tamanho")
        }
    }

    private fun detectUsbTargets() {
        clearUsbTarget("Nova busca de pendrive")
        val devices = usbHostInspector.massStorageUsbDevices()
        if (devices.isEmpty()) {
            showMessage("Nenhum pendrive USB Mass Storage foi detectado")
            return
        }
        val pending = devices.firstOrNull { !usbHostInspector.hasPermission(it) }
        if (pending != null) {
            usbController.requestPermission(pending, ACTION_USB_PERMISSION)
            showMessage("Autorize o pendrive e toque em Detectar pendrive novamente")
            return
        }

        runBusy("Consultando pendrives por SCSI READ CAPACITY…") {
            val inspected = withContext(Dispatchers.IO) {
                devices.map { device ->
                    device to massStorageBackend.open(device).use { it.inspect() }
                }
            }
            MaterialAlertDialogBuilder(this@StandaloneArmbianWizardActivity)
                .setTitle("Selecione o pendrive que poderá ser apagado")
                .setItems(inspected.map { it.second.label }.toTypedArray()) { _, index ->
                    selectedUsbDeviceName = inspected[index].first.deviceName
                    selectedUsbTarget = inspected[index].second
                    appendLog("Pendrive selecionado: ${inspected[index].second.label}")
                    updateUi()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }

    private fun writeImageToUsb(file: File?, label: String) {
        val image = file
        val target = selectedUsbTarget
        val device = selectedUsbDeviceName?.let(usbHostInspector::findDevice)
        if (image == null || !image.isFile) {
            showMessage("Selecione a imagem $label primeiro")
            return
        }
        if (target == null || device == null) {
            showMessage("Detecte e selecione o pendrive primeiro")
            return
        }
        if (!SafetyGate.snapshot(this).writeReady) {
            showMessage("Alimentação insuficiente para uma gravação segura")
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Apagar e gravar $label")
            .setMessage(
                "O aplicativo gravará ${image.name} diretamente em ${target.label} por SCSI e fará readback completo. Todo o conteúdo atual será apagado.",
            )
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("APAGAR, GRAVAR E VERIFICAR") { _, _ ->
                runBusy("Gravando pendrive sem root…") {
                    var lastWriting = -1
                    var lastVerifying = -1
                    withContext(Dispatchers.IO) {
                        massStorageBackend.open(device).use { session ->
                            val current = session.inspect()
                            require(current.deviceName == target.deviceName && current.sizeBytes == target.sizeBytes) {
                                "O pendrive mudou desde a seleção; detecte-o novamente"
                            }
                            session.writeImage(image, verify = true) { progress ->
                                val percent = ((progress.completedBytes * 100) / progress.totalBytes).toInt()
                                val shouldLog = when (progress.phase) {
                                    UsbMassStorageDirectBackend.ProgressPhase.WRITING -> {
                                        if (percent >= lastWriting + 5 || percent == 100) {
                                            lastWriting = percent
                                            true
                                        } else false
                                    }
                                    UsbMassStorageDirectBackend.ProgressPhase.VERIFYING -> {
                                        if (percent >= lastVerifying + 5 || percent == 100) {
                                            lastVerifying = percent
                                            true
                                        } else false
                                    }
                                }
                                if (shouldLog) runOnUiThread {
                                    appendLog("${progress.phase.name}: $percent%")
                                }
                            }
                        }
                    }
                    showMessage("Pendrive gravado e verificado sem aplicativos externos")
                }
            }
            .show()
    }

    private fun confirmBootstrapInstall() {
        val device = currentRockchipDevice() ?: return
        val file = ubootFile
        if (flashKind != FlashKind.NAND) {
            showMessage("Confirme primeiro que o armazenamento é NAND")
            return
        }
        if (file == null || !file.isFile) {
            showMessage("Selecione o u-boot-main.img oficial")
            return
        }
        if (backupFile?.isFile != true && !binding.existingBackupCheckBox.isChecked) {
            showMessage("Faça um backup ou confirme que já possui um backup verificado")
            return
        }
        val inspection = ArmbianImageValidator.inspect(file, ImageRole.UBOOT_MAIN)
        if (!inspection.accepted) {
            showMessage(inspection.message)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Gravar bootstrap no LBA 0x4000")
            .setMessage("A gravação será feita pelo backend RockUSB interno, seguida de leitura e comparação byte a byte.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("GRAVAR E VERIFICAR") { _, _ -> installBootstrap(device, file) }
            .show()
    }

    private fun installBootstrap(device: UsbDevice, file: File) {
        runBusy("Gravando bootstrap sem root…") {
            withContext(Dispatchers.IO) {
                rockBackend.open(device).use { session ->
                    session.writeFileAtLba(file, startSector = 0x4000L, verify = true)
                }
            }
            showMessage("Bootstrap gravado e verificado no LBA 0x4000")
        }
    }

    private fun stageFile(uri: Uri, role: ImageRole) {
        runBusy("Preparando ${role.name.lowercase()}…") {
            val staged = withContext(Dispatchers.IO) { copyUriToStaging(uri, role) }
            val inspection = ArmbianImageValidator.inspect(staged, role)
            appendLog("${role.name}: ${inspection.message}; ${staged.name}; ${formatBytes(staged.length())}")
            if (!inspection.accepted) {
                staged.delete()
                showMessage(inspection.message)
                return@runBusy
            }
            when (role) {
                ImageRole.UBOOT_MAIN -> ubootFile = staged
                ImageRole.ARMBIAN -> armbianFile = staged
                ImageRole.MULTITOOL -> multitoolFile = staged
            }
            if (role == ImageRole.ARMBIAN && !inspection.legacyKernelLikely) {
                showMessage("Imagem válida para USB; steP-nand interno ainda exige imagem legacy compatível")
            }
        }
    }

    private fun copyUriToStaging(uri: Uri, role: ImageRole): File {
        val originalName = queryDisplayName(uri) ?: "${role.name.lowercase()}-${System.currentTimeMillis()}.img"
        val safeName = originalName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val compressed = safeName.lowercase().endsWith(".xz")
        val outputName = if (compressed) safeName.dropLast(3) else safeName
        val directory = File(getExternalFilesDir("staging") ?: filesDir, role.name.lowercase()).apply { mkdirs() }
        val target = File(directory, outputName)
        if (target.exists() && !target.delete()) error("Não foi possível substituir ${target.name}")
        val source = contentResolver.openInputStream(uri) ?: error("Não foi possível abrir o arquivo")
        val input = if (compressed) XZInputStream(source) else source
        input.use { stream ->
            target.outputStream().buffered(BUFFER_SIZE).use { output ->
                stream.copyTo(output, BUFFER_SIZE)
            }
        }
        require(target.length() > 0L) { "Arquivo preparado ficou vazio" }
        return target
    }

    private fun copyArmbianIntoImagesFolder(treeUri: Uri) {
        val image = armbianFile
        if (image == null || !image.isFile) {
            showMessage("Selecione a imagem Armbian primeiro")
            return
        }
        runCatching {
            contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        runBusy("Copiando Armbian para images…") {
            withContext(Dispatchers.IO) {
                val root = DocumentFile.fromTreeUri(this@StandaloneArmbianWizardActivity, treeUri)
                    ?: error("Pasta selecionada inválida")
                val images = if (root.name.equals("images", ignoreCase = true)) {
                    root
                } else {
                    root.findFile("images") ?: root.createDirectory("images")
                    ?: error("Não foi possível acessar/criar a pasta images")
                }
                images.findFile(image.name)?.delete()
                val destination = images.createFile("application/octet-stream", image.name)
                    ?: error("Não foi possível criar ${image.name}")
                contentResolver.openOutputStream(destination.uri, "w")!!.use { output ->
                    image.inputStream().buffered(BUFFER_SIZE).use { input -> input.copyTo(output, BUFFER_SIZE) }
                }
            }
            showMessage("Imagem copiada para images")
        }
    }

    private fun currentRockchipDevice(): UsbDevice? {
        val name = rockchipDeviceName
        if (name.isNullOrBlank()) {
            showMessage("Detecte a TV Box primeiro")
            return null
        }
        val device = usbController.findDevices().firstOrNull { it.deviceName == name }?.device
        if (device == null) {
            clearRockchipState("TV Box não está mais conectada")
            showMessage("TV Box desconectada")
            return null
        }
        if (!usbController.hasPermission(device)) {
            usbController.requestPermission(device, ACTION_USB_PERMISSION)
            showMessage("Autorize novamente o acesso USB")
            return null
        }
        return device
    }

    private fun clearRockchipState(reason: String) {
        if (rockchipDeviceName != null) appendLog(reason)
        rockchipDeviceName = null
        rockchipIdentity = null
        flashInfo = null
        flashKind = FlashKind.UNKNOWN
        backupFile = null
        binding.existingBackupCheckBox.isChecked = false
        updateUi()
    }

    private fun clearUsbTarget(reason: String) {
        if (selectedUsbTarget != null) appendLog(reason)
        selectedUsbDeviceName = null
        selectedUsbTarget = null
        updateUi()
    }

    private fun runBusy(message: String, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        binding.progressBar.visibility = android.view.View.VISIBLE
        appendLog(message)
        updateUi()
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RockFlash:StandaloneWizard")
        wakeLock.acquire(12 * 60 * 60 * 1000L)
        lifecycleScope.launch {
            try {
                block()
            } catch (error: Throwable) {
                appendLog("ERRO: ${error.message ?: error.javaClass.simpleName}")
                showMessage(error.message ?: "Erro inesperado")
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
                busy = false
                binding.progressBar.visibility = android.view.View.GONE
                updateUi()
            }
        }
    }

    private fun updateUi() = with(binding) {
        val flash = flashInfo
        boxStatusText.text = buildString {
            append("Rockchip: ").append(rockchipDeviceName ?: "não detectado")
            append("\nArmazenamento: ").append(flashKind.name)
            append("\nTamanho: ").append(flash?.let { "${it.sizeMb} MB" } ?: "desconhecido")
            append("\nBackend: AUTÔNOMO — Android USB Host/RockUSB")
        }
        backupStatusText.text = backupFile?.let { "${it.absolutePath}\n${formatBytes(it.length())}" }
            ?: "Nenhum backup criado neste assistente"
        ubootStatusText.text = ubootFile?.let { "${it.name} • ${formatBytes(it.length())}" } ?: "Não selecionado"
        armbianStatusText.text = armbianFile?.let { "${it.name} • ${formatBytes(it.length())}" } ?: "Não selecionado"
        multitoolStatusText.text = multitoolFile?.let { "${it.name} • ${formatBytes(it.length())}" } ?: "Não selecionado"
        usbTargetText.text = selectedUsbTarget?.label ?: usbHostSummary ?: "Nenhum pendrive selecionado"

        val enabled = !busy
        detectBoxButton.isEnabled = enabled
        forceNandButton.isEnabled = enabled
        forceEmmcButton.isEnabled = enabled
        backupButton.isEnabled = enabled && rockchipDeviceName != null && flash != null
        selectUbootButton.isEnabled = enabled
        selectArmbianButton.isEnabled = enabled
        selectMultitoolButton.isEnabled = enabled
        detectUsbButton.isEnabled = enabled
        writeArmbianUsbButton.isEnabled = enabled && armbianFile != null && selectedUsbTarget != null
        writeMultitoolUsbButton.isEnabled = enabled && multitoolFile != null && selectedUsbTarget != null
        copyArmbianButton.isEnabled = enabled && armbianFile != null
        installBootstrapButton.isEnabled = enabled && ubootFile != null && rockchipDeviceName != null

        finalInstructionsText.text = when {
            flashKind == FlashKind.NAND && multitoolFile != null && armbianFile != null && ubootFile != null ->
                "Modo autônomo ativo. Grave o Multitool por SCSI, copie a imagem legacy para images, grave o bootstrap por RockUSB e inicialize pelo USB. A etapa steP-nand continua dentro do próprio Multitool da TV Box."
            flashKind == FlashKind.NAND && armbianFile != null ->
                "O APK já pode preparar e gravar o USB e o bootstrap sem root. Para NAND interna, use uma imagem legacy compatível dentro do Multitool."
            else -> "Nenhum aplicativo externo é necessário. Conceda apenas a permissão USB do Android e siga as etapas na ordem."
        }
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

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return null
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(BUFFER_SIZE).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun ByteArray.toHex(): String = joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }

    private fun appendLog(message: String) {
        val current = binding.logText.text?.toString().orEmpty()
        binding.logText.text = if (current.isBlank()) message else "$current\n$message"
    }

    private fun showMessage(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
        else -> "%.2f KB".format(bytes / 1024.0)
    }

    private data class DirectProbe(
        val chip: ByteArray,
        val flashId: ByteArray,
        val capability: ByteArray,
        val storage: Int,
        val flash: RockUsbDirectBackend.RockUsbFlashInfo,
    )

    private enum class FlashKind {
        NAND,
        EMMC,
        UNKNOWN,
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.tayson.rockflash.STANDALONE_USB_PERMISSION"
        private const val BUFFER_SIZE = 1024 * 1024
    }
}
