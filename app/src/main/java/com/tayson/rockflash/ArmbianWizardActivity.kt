package com.tayson.rockflash

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.tayson.rockflash.armbian.ArmbianImageValidator
import com.tayson.rockflash.armbian.ImageRole
import com.tayson.rockflash.databinding.ActivityArmbianWizardBinding
import com.tayson.rockflash.root.RemovableBlockDevice
import com.tayson.rockflash.root.RkDevelopToolBackend
import com.tayson.rockflash.root.UsbBlockBackend
import com.tayson.rockflash.safety.SafetyGate
import com.tayson.rockflash.usb.RockchipUsbController
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tukaani.xz.XZInputStream

class ArmbianWizardActivity : AppCompatActivity() {
    private lateinit var binding: ActivityArmbianWizardBinding
    private lateinit var usbController: RockchipUsbController
    private val rkBackend = RkDevelopToolBackend()
    private val blockBackend = UsbBlockBackend()

    private var pendingRole: ImageRole? = null
    private var ubootFile: File? = null
    private var armbianFile: File? = null
    private var multitoolFile: File? = null
    private var selectedBlockDevice: RemovableBlockDevice? = null
    private var rockchipNode: String? = null
    private var flashSizeMb: Long? = null
    private var flashKind: FlashKind = FlashKind.UNKNOWN
    private var backupFile: File? = null
    private var busy = false

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val role = pendingRole
        pendingRole = null
        if (uri != null && role != null) stageFile(uri, role)
    }

    private val imagesFolderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) copyArmbianIntoImagesFolder(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityArmbianWizardBinding.inflate(layoutInflater)
        setContentView(binding.root)
        usbController = RockchipUsbController(this)
        configureActions()
        appendLog("Assistente Armbian iniciado")
        updateUi()
    }

    private fun configureActions() = with(binding) {
        closeButton.setOnClickListener { finish() }
        detectBoxButton.setOnClickListener { detectBox() }
        backupButton.setOnClickListener { createFullBackup() }

        selectUbootButton.setOnClickListener { selectFile(ImageRole.UBOOT_MAIN) }
        selectArmbianButton.setOnClickListener { selectFile(ImageRole.ARMBIAN) }
        selectMultitoolButton.setOnClickListener { selectFile(ImageRole.MULTITOOL) }

        detectUsbButton.setOnClickListener { detectUsbTargets() }
        writeArmbianUsbButton.setOnClickListener { writeImageToUsb(armbianFile, "Armbian") }
        writeMultitoolUsbButton.setOnClickListener { writeImageToUsb(multitoolFile, "Multitool") }
        copyArmbianButton.setOnClickListener { imagesFolderPicker.launch(null) }
        installBootstrapButton.setOnClickListener { confirmBootstrapInstall() }
        refreshButton.setOnClickListener { updateUi() }
    }

    private fun selectFile(role: ImageRole) {
        pendingRole = role
        filePicker.launch(arrayOf("application/octet-stream", "application/x-xz", "*/*"))
    }

    private fun detectBox() {
        val device = usbController.findDevices().firstOrNull()
        if (device == null) {
            showMessage("Nenhuma TV Box Rockchip VID 2207 foi detectada")
            return
        }
        rockchipNode = device.deviceName
        runBusy("Detectando chip e armazenamento…") {
            val chip = rkBackend.runReadOnly(RkDevelopToolBackend.ReadOnlyCommand.READ_CHIP_INFO, device.deviceName)
            val flash = rkBackend.runReadOnly(RkDevelopToolBackend.ReadOnlyCommand.READ_FLASH_INFO, device.deviceName)
            val combined = "${chip.output}\n${flash.output}"
            flashSizeMb = parseFlashSizeMb(combined)
            flashKind = when {
                combined.contains("NAND", ignoreCase = true) || combined.contains("rknand", ignoreCase = true) -> FlashKind.NAND
                combined.contains("EMMC", ignoreCase = true) || combined.contains("eMMC", ignoreCase = true) -> FlashKind.EMMC
                else -> FlashKind.UNKNOWN
            }
            appendLog("RCI exit=${chip.exitCode}: ${chip.output}")
            appendLog("RFI exit=${flash.exitCode}: ${flash.output}")
            if (!chip.success || !flash.success) {
                showMessage("A detecção não foi concluída; consulte o log")
            }
        }
    }

    private fun createFullBackup() {
        val node = rockchipNode
        val sizeMb = flashSizeMb
        if (node.isNullOrBlank() || sizeMb == null || sizeMb <= 0L) {
            showMessage("Detecte a TV Box e o tamanho da memória primeiro")
            return
        }
        if (!SafetyGate.snapshot(this).writeReady) {
            showMessage("Conecte o carregador, mantenha bateria acima de 50% e desative economia de energia")
            return
        }

        val directory = File(getExternalFilesDir("backups") ?: filesDir, "rockflash").apply { mkdirs() }
        val output = File(directory, "rk322x-${flashKind.name.lowercase()}-${System.currentTimeMillis()}.img")
        val sectors = sizeMb * 2048L
        runBusy("Criando backup completo de $sizeMb MB…") {
            val result = rkBackend.backupFlash(node, output.absolutePath, sectors)
            appendLog("BACKUP exit=${result.exitCode}: ${result.output}")
            if (result.success && output.length() > 0L) {
                backupFile = output
                val digest = withContext(Dispatchers.IO) { sha256(output) }
                File(output.parentFile, "${output.name}.sha256").writeText("$digest  ${output.name}\n")
                showMessage("Backup concluído e hash SHA-256 gerado")
            } else {
                showMessage("Falha no backup; consulte o log")
            }
        }
    }

    private fun detectUsbTargets() {
        runBusy("Procurando armazenamento USB removível…") {
            val result = blockBackend.listCandidates()
            result.onSuccess { devices ->
                if (devices.isEmpty()) {
                    showMessage("Nenhum dispositivo /dev/sdX removível foi encontrado")
                    return@onSuccess
                }
                val labels = devices.map { it.label }.toTypedArray()
                MaterialAlertDialogBuilder(this@ArmbianWizardActivity)
                    .setTitle("Selecione o pendrive que será apagado")
                    .setItems(labels) { _, index ->
                        selectedBlockDevice = devices[index]
                        appendLog("USB selecionado: ${devices[index].label}")
                        updateUi()
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }.onFailure { error ->
                appendLog("Falha ao listar USB: ${error.message}")
                showMessage(error.message ?: "Falha ao listar dispositivos USB")
            }
        }
    }

    private fun writeImageToUsb(file: File?, label: String) {
        val image = file
        val target = selectedBlockDevice
        if (image == null || !image.isFile) {
            showMessage("Selecione a imagem $label primeiro")
            return
        }
        if (target == null) {
            showMessage("Detecte e selecione o pendrive primeiro")
            return
        }
        if (!SafetyGate.snapshot(this).writeReady) {
            showMessage("Alimentação insuficiente para gravar o pendrive com segurança")
            return
        }

        val message = "Todo o conteúdo de ${target.label} será apagado e substituído por ${image.name}."
        MaterialAlertDialogBuilder(this)
            .setTitle("Gravar $label no pendrive")
            .setMessage(message)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("APAGAR E GRAVAR") { _, _ ->
                runBusy("Gravando ${image.name} em ${target.node}…") {
                    val result = blockBackend.writeImage(image.absolutePath, target, verify = true)
                    appendLog("USB WRITE exit=${result.exitCode}: ${result.output}")
                    showMessage(if (result.success) "Pendrive gravado" else "Falha ao gravar pendrive")
                }
            }
            .show()
    }

    private fun confirmBootstrapInstall() {
        val node = rockchipNode
        val file = ubootFile
        if (node.isNullOrBlank()) {
            showMessage("Detecte a TV Box em Loader/MaskROM primeiro")
            return
        }
        if (flashKind != FlashKind.NAND) {
            showMessage("Este bootstrap é destinado ao fluxo RK322x com NAND")
            return
        }
        if (file == null || !file.isFile) {
            showMessage("Selecione o u-boot-main.img oficial")
            return
        }
        if (backupFile?.isFile != true && !binding.existingBackupCheckBox.isChecked) {
            showMessage("Faça um backup ou marque que já possui backup verificado")
            return
        }
        val inspection = ArmbianImageValidator.inspect(file, ImageRole.UBOOT_MAIN)
        if (!inspection.accepted) {
            showMessage(inspection.message)
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Instalar bootstrap USB na NAND")
            .setMessage(
                "Será gravado u-boot-main.img no LBA 0x4000 (16384). O Android interno poderá deixar de iniciar até a instalação ser concluída pelo Multitool.",
            )
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("INSTALAR") { _, _ -> installBootstrap(node, file) }
            .show()
    }

    private fun installBootstrap(node: String, file: File) {
        runBusy("Gravando bootstrap no LBA 0x4000…") {
            val result = rkBackend.runWrite(
                command = RkDevelopToolBackend.WriteCommand.WRITE_RAW_LBA,
                usbDeviceNode = node,
                filePath = file.absolutePath,
                startSector = 0x4000L,
                confirmation = "FLASH",
            )
            appendLog("BOOTSTRAP exit=${result.exitCode}: ${result.output}")
            showMessage(if (result.success) "Bootstrap instalado" else "Falha na instalação do bootstrap")
        }
    }

    private fun stageFile(uri: Uri, role: ImageRole) {
        runBusy("Preparando ${role.name.lowercase()}…") {
            val staged = withContext(Dispatchers.IO) { copyUriToStaging(uri, role) }
            val inspection = ArmbianImageValidator.inspect(staged, role)
            appendLog("${role.name}: ${inspection.message}; arquivo=${staged.name}; tamanho=${formatBytes(staged.length())}")
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
                showMessage("Imagem válida para USB; para instalar na NAND via steP-nand use kernel legacy 4.4")
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
        runBusy("Copiando Armbian para a pasta images do Multitool…") {
            withContext(Dispatchers.IO) {
                val root = DocumentFile.fromTreeUri(this@ArmbianWizardActivity, treeUri)
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

    private fun runBusy(message: String, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        binding.progressBar.visibility = android.view.View.VISIBLE
        appendLog(message)
        updateUi()
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RockFlash:ArmbianWizard")
        wakeLock.acquire(12 * 60 * 60 * 1000L)
        lifecycleScope.launch {
            try {
                block()
            } catch (error: Throwable) {
                appendLog("ERRO: ${error.message}")
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
        val box = usbController.findDevices().firstOrNull()
        if (box != null && rockchipNode == null) rockchipNode = box.deviceName
        boxStatusText.text = buildString {
            append("Rockchip: ")
            append(if (rockchipNode == null) "não detectado" else rockchipNode)
            append("\nArmazenamento: ${flashKind.name}")
            append("\nTamanho: ${flashSizeMb?.let { "$it MB" } ?: "desconhecido"}")
        }
        backupStatusText.text = backupFile?.let { "${it.absolutePath}\n${formatBytes(it.length())}" }
            ?: "Nenhum backup criado neste assistente"
        ubootStatusText.text = ubootFile?.let { "${it.name} • ${formatBytes(it.length())}" } ?: "Não selecionado"
        armbianStatusText.text = armbianFile?.let { "${it.name} • ${formatBytes(it.length())}" } ?: "Não selecionado"
        multitoolStatusText.text = multitoolFile?.let { "${it.name} • ${formatBytes(it.length())}" } ?: "Não selecionado"
        usbTargetText.text = selectedBlockDevice?.label ?: "Nenhum pendrive selecionado"

        val enabled = !busy
        detectBoxButton.isEnabled = enabled
        backupButton.isEnabled = enabled && rockchipNode != null && flashSizeMb != null
        selectUbootButton.isEnabled = enabled
        selectArmbianButton.isEnabled = enabled
        selectMultitoolButton.isEnabled = enabled
        detectUsbButton.isEnabled = enabled
        writeArmbianUsbButton.isEnabled = enabled && armbianFile != null && selectedBlockDevice != null
        writeMultitoolUsbButton.isEnabled = enabled && multitoolFile != null && selectedBlockDevice != null
        copyArmbianButton.isEnabled = enabled && armbianFile != null
        installBootstrapButton.isEnabled = enabled && ubootFile != null && rockchipNode != null

        finalInstructionsText.text = when {
            flashKind == FlashKind.NAND && multitoolFile != null && armbianFile != null && ubootFile != null ->
                "Para NAND: grave o Multitool no pendrive, reconecte o pendrive ao celular, copie a imagem Armbian legacy para images, instale o bootstrap e inicialize a TV Box pelo USB OTG. No Multitool escolha Burn Armbian image via steP-nand."
            flashKind == FlashKind.NAND && armbianFile != null ->
                "Para usar kernel atual, grave diretamente o Armbian no pendrive e instale o bootstrap; o sistema será executado pelo USB. Para NAND interna, use Multitool + imagem legacy 4.4 + steP-nand."
            else -> "Siga as etapas na ordem. Nenhuma imagem é considerada universal entre placas diferentes."
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return null
    }

    private fun parseFlashSizeMb(text: String): Long? {
        val patterns = listOf(
            Regex("Flash\\s*Size\\s*[:=]\\s*(\\d+)\\s*MB", RegexOption.IGNORE_CASE),
            Regex("Size\\s*[:=]\\s*(\\d+)\\s*MB", RegexOption.IGNORE_CASE),
        )
        return patterns.firstNotNullOfOrNull { it.find(text)?.groupValues?.getOrNull(1)?.toLongOrNull() }
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

    private enum class FlashKind {
        NAND,
        EMMC,
        UNKNOWN,
    }

    companion object {
        private const val BUFFER_SIZE = 1024 * 1024
    }
}
