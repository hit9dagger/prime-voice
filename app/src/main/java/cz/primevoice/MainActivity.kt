package cz.primevoice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import cz.primevoice.engine.Models
import cz.primevoice.engine.Translation
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.update
import java.io.File

class MainActivity : ComponentActivity() {
    private var requestedFile: Uri? = null
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startSession()
        else PrimeState.error("MIC_PERMISSION", "Bez oprávnění k mikrofonu nelze spustit poslech.")
    }
    private val selectFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: SecurityException) { }
            requestedFile = uri
            requestStart()
        }
    }
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) lifecycleScope.launch(Dispatchers.IO) {
            try { contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(PrimeState.diagnostics(this@MainActivity)) } }
            catch (_: Exception) { PrimeState.error("DIAGNOSTICS_WRITE", "Diagnostiku se nepodařilo uložit.") }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshModels()
        setContent {
            val ui by PrimeState.ui.collectAsState()
            var settingsOpen by remember { mutableStateOf(false) }
            var clearConfirm by remember { mutableStateOf(false) }
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFF36DDD3), secondary = Color(0xFF5F9DFF),
                background = Color(0xFF080F1B), surface = Color(0xFF131F30))) {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                    .windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("PRIME VOICE", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
                    Text("AI Game Translator · ENGLISH → ČEŠTINA", color = MaterialTheme.colorScheme.onBackground)
                    Text(ui.status, color = MaterialTheme.colorScheme.primary)
                    LinearProgressIndicator(progress = { ui.level.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Button(onClick = {
                        if (ui.active) stopService(Intent(this@MainActivity, PrimeService::class.java))
                        else { requestedFile = null; requestStart() }
                    }, enabled = ui.active || (ui.modelsReady && ui.modelProgress.isBlank()),
                        modifier = Modifier.fillMaxWidth().height(76.dp)) {
                        Text(if (ui.active) "STOP" else "START", style = MaterialTheme.typography.headlineMedium)
                    }
                    if (ui.error.isNotBlank()) Card(Modifier.fillMaxWidth()) {
                        Text(ui.error, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error)
                    }
                    Text(if (ui.modelsReady) "Lokální ASR a překladové modely jsou připravené."
                        else "Nejdřív stáhněte modely a nainstalujte český hlas.",
                        color = MaterialTheme.colorScheme.onBackground)
                    if (ui.modelProgress.isNotBlank()) Text(ui.modelProgress, color = MaterialTheme.colorScheme.primary)
                    if (!ui.active) {
                        Button(onClick = { downloadModels(false) }, enabled = ui.modelProgress.isBlank()) {
                            Text("STÁHNOUT / OVĚŘIT MODELY · asi 42 + 30 MB")
                        }
                        OutlinedButton(onClick = { openUrl(PIPER_URL) }) { Text("INSTALOVAT NEURONOVÝ HLAS PIPER JIRKA") }
                    }
                    Text("Piper se instaluje jako samostatný bezplatný hlasový engine. Po instalaci restartujte PRIME VOICE. Má jeden český hlas.",
                        color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.bodySmall)
                    if (ui.engine.isNotBlank()) Text("Hlas: " + ui.engine, color = MaterialTheme.colorScheme.secondary)
                    Text(ui.route.ifBlank { "Připojte Bluetooth, USB nebo kabelová sluchátka." }, color = MaterialTheme.colorScheme.secondary)
                    Text("Reproduktor telefonu není povolen: překladač by mohl znovu zachytit vlastní český hlas.",
                        color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { settingsOpen = !settingsOpen }) { Text("NASTAVENÍ A DIAGNOSTIKA") }
                    if (settingsOpen) {
                        Text("Hlasitost", color = MaterialTheme.colorScheme.onBackground)
                        Slider(value = ui.volume, onValueChange = { value -> PrimeState.ui.update { it.copy(volume = value) } })
                        Text("Základní rychlost: %.2f× · automatické maximum 1,35×".format(ui.speed), color = MaterialTheme.colorScheme.onBackground)
                        Slider(value = ui.speed, valueRange = 1f..1.35f, onValueChange = { value -> PrimeState.ui.update { it.copy(speed = value) } })
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Switch(checked = ui.characterVoice, enabled = !ui.active && ui.modelProgress.isBlank(), onCheckedChange = { enabled ->
                                if (enabled && !Models(this@MainActivity).speakerReady()) downloadModels(true)
                                else PrimeState.ui.update { it.copy(characterVoice = enabled) }
                            })
                            Text("CHARACTER VOICE (experimentální)", color = MaterialTheme.colorScheme.onBackground)
                        }
                        Text("Hlasové profily potřebují delší čistou repliku. Krátké a nejasné věty používají výchozí hlas. Oddělování současné řeči není implementováno.",
                            color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.bodySmall)
                        ui.speakerVoices.forEach { (speaker, selected) ->
                            var expanded by remember(speaker) { mutableStateOf(false) }
                            Column {
                                Text(speaker, color = MaterialTheme.colorScheme.secondary)
                                OutlinedButton(onClick = { expanded = true }) { Text(selected.ifBlank { "Výchozí český hlas" }) }
                                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                    ui.voices.forEach { voice ->
                                        DropdownMenuItem(text = { Text(voice.first) }, onClick = {
                                            PrimeState.ui.update { it.copy(speakerVoices = it.speakerVoices + (speaker to voice.first)) }
                                            expanded = false
                                        })
                                    }
                                }
                            }
                        }
                        Text("Oprava hlasu platí pro další překlady. Již uložené věty zachovávají přiřazený hlas.",
                            color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { openSettings("com.android.settings.TTS_SETTINGS") }) { Text("SYSTÉMOVÉ ČESKÉ HLASY") }
                        OutlinedButton(onClick = { openSettings(Settings.ACTION_BLUETOOTH_SETTINGS) }) { Text("BLUETOOTH SLUCHÁTKA") }
                        OutlinedButton(onClick = { openSettings(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) }) { Text("NASTAVENÍ BATERIE POCO") }
                        OutlinedButton(enabled = !ui.active && ui.modelsReady, onClick = { selectFile.launch(arrayOf("audio/wav", "audio/x-wav", "audio/*")) }) {
                            Text("TEST ZE SOUBORU · WAV 16 kHz / mono / PCM16")
                        }
                        Text("ASR: ${ui.asrMs} ms · Překlad: ${ui.translationMs} ms\nSyntéza: ${ui.synthesisMs} ms · Do začátku přehrávání: ${ui.latencyMs} ms\nFronta: ${ui.backlog}/24 · Nezpracované vstupní bloky: ${ui.missedBlocks}",
                            color = MaterialTheme.colorScheme.onBackground)
                        Button(onClick = { export.launch("prime-diagnostics.json") }) { Text("EXPORTOVAT DIAGNOSTIKU") }
                        Text("Export neobsahuje zvuk, přepisy ani překlady. Hotové věty čekající na přehrání se dočasně ukládají jen v telefonu a po přehrání mažou.",
                            color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(enabled = !ui.active, onClick = { clearConfirm = true }) { Text("VYMAZAT NEPŘEHRANOU FRONTU") }
                    }
                    val badge = resources.getIdentifier("google_translate_attribution", "drawable", packageName)
                    if (badge != 0) Image(painterResource(badge), "Powered by Google Translate", Modifier.width(150.dp))
                    else Text("Překlad: Google Translate (ML Kit, lokálně)", color = MaterialTheme.colorScheme.onBackground)
                    ui.lines.asReversed().forEach { line ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(line.speaker + " · " + line.status, color = MaterialTheme.colorScheme.secondary)
                                Text(line.english, style = MaterialTheme.typography.bodyMedium)
                                Text(line.czech, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                    Text("Testovací sestavení. Přesnost, Bluetooth a Android 16 vyžadují ověření na telefonu. Při zahlcení se příjem pozastaví, dokončené překlady se nezahazují.",
                        color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(20.dp))
                }
                if (clearConfirm) AlertDialog(onDismissRequest = { clearConfirm = false },
                    title = { Text("Vymazat nepřehrané překlady?") },
                    text = { Text("Tímto vědomě odstraníte uložené věty a jejich zvuk.") },
                    confirmButton = { TextButton(onClick = {
                        lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                PendingStore(File(noBackupFilesDir, "pending")).clear()
                                PrimeState.ui.update { it.copy(backlog = 0, lines = emptyList()) }
                            } catch (_: Exception) { PrimeState.error("QUEUE_CLEAR", "Frontu se nepodařilo vymazat.") }
                        }
                        clearConfirm = false
                    }) { Text("Vymazat") } },
                    dismissButton = { TextButton(onClick = { clearConfirm = false }) { Text("Zrušit") } })
            }
        }
    }
    private fun requestStart() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 31) needed.add(Manifest.permission.BLUETOOTH_CONNECT)
        permissions.launch(needed.toTypedArray())
    }
    private fun startSession() {
        try {
            ContextCompat.startForegroundService(this, Intent(this, PrimeService::class.java).setData(requestedFile))
        } catch (_: Exception) { PrimeState.error("START_DENIED", "Android nepovolil spuštění mikrofonu. Otevřete aplikaci a zkuste START znovu.") }
    }
    private fun refreshModels() = lifecycleScope.launch {
        val translator = Translation()
        try {
            val ready = withContext(Dispatchers.IO) { Models(this@MainActivity).asrReady() } && translator.ready()
            PrimeState.ui.update { it.copy(modelsReady = ready) }
        } catch (_: Exception) { PrimeState.ui.update { it.copy(modelsReady = false) } }
        finally { translator.close() }
    }
    private fun downloadModels(speakers: Boolean) = lifecycleScope.launch {
        PrimeState.ui.update { it.copy(modelProgress = "Připravuji stahování…", error = "") }
        val translator = Translation()
        try {
            val models = Models(this@MainActivity)
            if (speakers) {
                models.downloadSpeaker { progress -> PrimeState.ui.update { it.copy(modelProgress = progress) } }
                PrimeState.ui.update { it.copy(characterVoice = true) }
            } else {
                models.downloadAsr { progress -> PrimeState.ui.update { it.copy(modelProgress = progress) } }
                PrimeState.ui.update { it.copy(modelProgress = "Stahuji lokální překlad Google EN → CS (přibližně 30 MB)…") }
                translator.prepare()
                PrimeState.ui.update { it.copy(modelsReady = true) }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { PrimeState.error("MODEL_DOWNLOAD", "Stažení nebo ověření modelu selhalo. Zkontrolujte internet a místo; lze bezpečně opakovat.") }
        finally { translator.close(); PrimeState.ui.update { it.copy(modelProgress = "") } }
    }
    private fun openUrl(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: Exception) { PrimeState.error("NO_BROWSER", "V telefonu chybí aplikace pro otevření odkazu.") }
    }
    private fun openSettings(action: String) {
        try { startActivity(Intent(action)) }
        catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }
    companion object {
        const val PIPER_URL = "https://huggingface.co/csukuangfj2/sherpa-onnx-apk/resolve/main/tts-engine-new/1.13.8/sherpa-onnx-1.13.8-arm64-v8a-ces-tts-engine-vits-piper-cs_CZ-jirka-medium.apk"
    }
}
