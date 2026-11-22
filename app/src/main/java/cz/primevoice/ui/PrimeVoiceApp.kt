package cz.primevoice.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.primevoice.PrimeVoiceViewModel
import cz.primevoice.core.Stage
import cz.primevoice.core.ExecutionMode
import cz.primevoice.core.GIB
import cz.primevoice.core.MIB
import cz.primevoice.translation.LocalModelCatalog
import kotlin.math.roundToInt
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun PrimeVoiceApp(vm: PrimeVoiceViewModel) = PrimeTheme {
    val session by vm.session.collectAsStateWithLifecycle()
    val settings by vm.settings.state.collectAsStateWithLifecycle()
    val queue by vm.queue.state.collectAsStateWithLifecycle()
    val model by vm.translation.state.collectAsStateWithLifecycle()
    val execution by vm.translation.execution.collectAsStateWithLifecycle()
    val transfer by vm.translation.models.state.collectAsStateWithLifecycle()
    val tts by vm.tts.state.collectAsStateWithLifecycle()
    val camera by vm.camera.state.collectAsStateWithLifecycle()
    val asr by vm.asr.state.collectAsStateWithLifecycle()
    val output by vm.output.state.collectAsStateWithLifecycle()
    val diagnostic by vm.diagnostics.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var editRegion by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(session.running) { if (!session.running) editRegion = false }
    var cameraOptions by rememberSaveable { mutableStateOf(false) }
    var voiceOptions by rememberSaveable { mutableStateOf(false) }
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var clearConfirm by remember { mutableStateOf(false) }
    var voiceMenu by remember { mutableStateOf(false) }
    var wifiOnly by rememberSaveable { mutableStateOf(true) }
    val importModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.translation.prepare(wifiOnly, uri)
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.start() else vm.message("Kamera nebyla povolena. Oprávnění lze změnit v nastavení aplikace.")
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.microphone(granted)
        if (!granted) vm.message("Mikrofon nebyl povolen. Kamerový překlad funguje dál.")
    }
    DisposableEffect(session.running) {
        val window = (context as? Activity)?.window
        if (session.running) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    val waiting = queue.waiting + session.retained
    val active = queue.items.firstOrNull { it.stage == Stage.SPEAKING }
    val displayed = active ?: queue.lastReady ?: queue.lastSpoken
    val age = queue.items.firstOrNull()?.let { ((session.now - it.subtitle.capturedAt).coerceAtLeast(0) / 1000f) } ?: 0f
    Scaffold(containerColor = Background, bottomBar = {
        Surface(color = Background, shadowElevation = 10.dp) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
                Button(onClick = {
                    if (session.running) { editRegion = false; vm.stop() }
                    else if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) vm.start()
                    else cameraPermission.launch(Manifest.permission.CAMERA)
                }, enabled = session.running || !tts.speaking,
                    modifier = Modifier.fillMaxWidth().height(58.dp), shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (session.running) Color(0xFF293742) else Lime,
                        contentColor = if (session.running) Color.White else Background)) {
                    Icon(if (session.running) PrimeIcons.Stop else PrimeIcons.PlayArrow, null)
                    Spacer(Modifier.width(10.dp))
                    Text(if (session.running) "ZASTAVIT" else if (session.ocrDiagnostic) "SPUSTIT TEST OCR" else "SPUSTIT PŘEKLAD", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
                Text(if (session.running) "Při zastavení se rozehraná věta dokončí." else "Bez propojení s PS5. Bez placených služeb.",
                    Modifier.fillMaxWidth().padding(top = 8.dp), color = Muted, style = MaterialTheme.typography.labelMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = Lime, shape = RoundedCornerShape(12.dp), modifier = Modifier.size(42.dp)) {
                        Icon(PrimeIcons.GraphicEq, null, Modifier.padding(8.dp), tint = Background)
                    }
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text("PRIME VOICE", style = MaterialTheme.typography.headlineSmall, letterSpacing = 1.sp)
                        Text("CAMERA 2.0 · VAŠE HRA. ČESKÝ HLAS.", style = MaterialTheme.typography.labelSmall, color = Muted)
                    }
                    IconButton(onClick = { showHelp = true }) { Icon(PrimeIcons.Info, "Nápověda", tint = Muted) }
                }
                Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tag("EN → CS", Color(0xFF273444))
                    Tag("OFFLINE", Color(0xFF22331C), Lime)
                    Tag(if (session.running) "PŘEKLAD BĚŽÍ" else "PŘIPRAVENO KE HŘE", Panel, Muted)
                }
            }
            item {
                PanelCard {
                    Text("Výkon a modely", style = MaterialTheme.typography.titleLarge)
                    Text("RAM ${format(execution.resources.totalRam.toFloat() / GIB)} GiB · volná RAM ${format(execution.resources.availableRam.toFloat() / GIB)} GiB · úložiště ${format(execution.resources.freeStorage.toFloat() / GIB)} GiB volné", color = Muted, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ExecutionMode.entries.forEach { mode ->
                            FilterChip(selected = execution.selected == mode, onClick = { vm.selectMode(mode) },
                                enabled = !session.running && !execution.busy, label = { Text(mode.name) })
                        }
                    }
                    Text("Aktivní: ${execution.effective} · doporučeno: ${execution.recommended}", color = Lime)
                    Text(execution.message, color = Muted)
                    if (execution.selected == ExecutionMode.LIGHT) {
                        Text("Úsporný režim aplikace: 720p, OCR nejvýše 2,5× za sekundu, jedna analýza/překlad současně. Celé titulky a hlasová fronta zůstávají zachované.", color = Muted, style = MaterialTheme.typography.bodySmall)
                    } else {
                        val spec = LocalModelCatalog.forMode(execution.selected)!!
                        Text("${spec.name} · ${format(spec.bytes.toFloat() / MIB)} MiB", style = MaterialTheme.typography.titleMedium)
                        Text(execution.engineStatus, color = Muted, style = MaterialTheme.typography.bodySmall)
                        if (execution.selected == ExecutionMode.STANDARD) Text(LocalModelCatalog.standardBlockedReason, color = Muted)
                        else if (execution.selected !in execution.verified) Text("Na tomto telefonu zatím neověřeno. Výběr sám model nezapne; do úspěšného testu překládá ML Kit.", color = Muted)
                        if (transfer.active) {
                            LinearProgressIndicator(progress = { (transfer.downloaded.toFloat() / transfer.total.coerceAtLeast(1)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                            Text("${transfer.downloaded / MIB} / ${transfer.total / MIB} MiB · během přípravy ponechte aplikaci otevřenou", color = Muted)
                        }
                        Text(transfer.message, color = Muted, style = MaterialTheme.typography.bodySmall)
                        if (!execution.installed && execution.selected == ExecutionMode.STANDARD) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(wifiOnly, { wifiOnly = it }, enabled = !execution.busy)
                                Text("Stahovat pouze přes Wi-Fi")
                            }
                            Button(onClick = { vm.translation.prepare(wifiOnly) }, enabled = !session.running && !execution.busy && vm.translation.rejection() == null) {
                                Text("Stáhnout model STANDARD")
                            }
                        }
                        if (!execution.installed && execution.selected == ExecutionMode.ULTRA) {
                            Text("Gemma vyžaduje přijetí podmínek vydavatele na Hugging Face. Stáhněte soubor gemma-3n-E2B-it-int4.litertlm (3,66 GB), poté jej importujte. Aplikace nevyžaduje API klíč.", color = Muted)
                            TextButton(onClick = { openUrl(context, spec.page) }) { Text("Oficiální stažení Gemma 3n E2B") }
                            OutlinedButton(onClick = { importModel.launch(arrayOf("*/*")) }, enabled = !session.running && !execution.busy && vm.translation.rejection() == null) { Text("Importovat stažený model") }
                        }
                        if (execution.installed) Button(onClick = { vm.translation.activate() },
                            enabled = !session.running && !execution.busy && model.ready && vm.translation.rejection() == null) { Text("Ověřit a zapnout model") }
                    }
                    if (execution.busy) TextButton(onClick = { vm.translation.cancelPreparation() }) { Text("Zrušit přípravu") }
                }
            }
            if (!model.ready || tts.selected.isBlank() || tts.loading) item {
                PanelCard {
                    Text("Příprava na první hru", style = MaterialTheme.typography.titleLarge)
                    Text(model.message, color = Muted)
                    if (!model.ready) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(wifiOnly, { wifiOnly = it }, enabled = !model.downloading)
                            Text("Stahovat pouze přes Wi-Fi", style = MaterialTheme.typography.bodyMedium)
                        }
                        Button(onClick = { vm.download(wifiOnly) }, enabled = !model.downloading, modifier = Modifier.fillMaxWidth()) {
                            if (model.downloading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Icon(PrimeIcons.Download, null)
                            Spacer(Modifier.width(8.dp)); Text(if (model.downloading) "Připravuji…" else "Stáhnout offline překladač")
                        }
                    }
                    if (tts.loading) Text("Vyhledávám nainstalované české offline hlasy…", color = Muted)
                    else if (tts.selected.isBlank()) {
                        Text(tts.error ?: "Nainstalujte český offline hlas.", color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = { openTtsSettings(context) }, modifier = Modifier.fillMaxWidth()) { Text("Nastavení hlasů Androidu") }
                        TextButton(onClick = vm::refreshVoices) { Text("Obnovit seznam hlasů") }
                    }
                }
            }
            item {
                SectionTitle("01", "Kamera", if (camera.active) "AKTIVNÍ" else "VYPNUTÁ")
                Spacer(Modifier.height(12.dp))
                key(execution.effective) {
                    SubtitleRegionSelector(vm.camera, session.running, settings.region, editRegion) { r ->
                        vm.updateSettings { it.copy(region = r) }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { editRegion = !editRegion; vm.calibrate(editRegion) }, enabled = session.running) {
                        Icon(PrimeIcons.CropFree, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                        Text(if (editRegion) "Hotovo" else "Vybrat oblast")
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { cameraOptions = !cameraOptions }) { Text("Upravit kameru") }
                }
                Text(if (editRegion) "Tažením vyznačte celý blok titulků. Oblast se uloží automaticky." else camera.status,
                    color = Muted, style = MaterialTheme.typography.bodyMedium)
                if (cameraOptions) PanelCard {
                    SettingSlider("Přiblížení", "${format(settings.zoom)}×", settings.zoom.coerceIn(1f, camera.zoomMax),
                        1f..camera.zoomMax) { vm.updateSettings { s -> s.copy(zoom = it) } }
                    if (camera.exposureMax > camera.exposureMin) SettingSlider("Expozice", settings.exposure.toString(),
                        settings.exposure.toFloat(), camera.exposureMin.toFloat()..camera.exposureMax.toFloat(),
                        (camera.exposureMax - camera.exposureMin - 1).coerceAtLeast(0)) { vm.updateSettings { s -> s.copy(exposure = it.roundToInt()) } }
                    ToggleRow("Zvýšit kontrast OCR", "Pomáhá slabým titulkům; při dobrém obrazu ponechte vypnuté.", settings.enhance) {
                        vm.updateSettings { s -> s.copy(enhance = it) }
                    }
                    Text("Klepnutím do náhledu zaostříte. Pro celou větu označte všechny řádky a vynechte herní HUD.", color = Muted,
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
            item {
                PanelCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(PrimeIcons.Mic, null, tint = if (settings.microphone) Lime else Muted)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text("Pomocný odposlech dialogů", style = MaterialTheme.typography.titleMedium)
                            Text(if (asr.listening) "POSLOUCHÁM" else if (settings.microphone) "ZAPNUTO" else "VYPNUTO",
                                color = if (settings.microphone) Lime else Muted, style = MaterialTheme.typography.labelSmall)
                        }
                        Switch(settings.microphone, { enabled ->
                            if (!enabled) vm.microphone(false)
                            else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.microphone(true)
                            else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        }, enabled = asr.available)
                    }
                    Text(if (!asr.available) "Offline rozpoznávač není dostupný. Kamera funguje samostatně."
                        else if (settings.microphone) asr.message else "Volitelná pomoc pro nejasná slova. Kamera má vždy přednost.",
                        color = Muted, style = MaterialTheme.typography.bodyMedium)
                }
            }
            item {
                SectionTitle("02", "Český dabing")
                Spacer(Modifier.height(12.dp))
                PanelCard {
                    SettingSlider("Zpoždění dabingu", "${settings.delaySeconds} s", settings.delaySeconds.toFloat(), 0f..10f, 9,
                        enabled = !session.running) { vm.updateSettings { s -> s.copy(delaySeconds = it.roundToInt()) } }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("0 s · ihned", color = Muted, style = MaterialTheme.typography.labelMedium)
                        Text("10 s · větší rezerva", color = Muted, style = MaterialTheme.typography.labelMedium)
                    }
                    Text("Počáteční rezerva se přidá jen jednou. Další věty navazují bez čekání.", color = Muted,
                        style = MaterialTheme.typography.bodyMedium)
                    HorizontalDivider(color = Outline)
                    Text("Český hlas", style = MaterialTheme.typography.titleMedium)
                    Box {
                        OutlinedButton(onClick = { voiceMenu = true }, enabled = !session.running && !tts.speaking && !tts.loading,
                            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                            Text(tts.voices.firstOrNull { it.id == tts.selected }?.name ?: "Vyberte offline hlas", Modifier.weight(1f))
                            Icon(PrimeIcons.ExpandMore, null)
                        }
                        DropdownMenu(voiceMenu, { voiceMenu = false }, modifier = Modifier.heightIn(max = 320.dp)) {
                            tts.voices.forEach { voice -> DropdownMenuItem(text = { Text(voice.label) }, onClick = { voiceMenu = false; vm.selectVoice(voice.id) }) }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = vm::previewVoice, enabled = !session.running && !tts.speaking && tts.selected.isNotBlank()) {
                            Icon(PrimeIcons.VolumeUp, null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)); Text("Ukázka hlasu")
                        }
                        TextButton(onClick = { voiceOptions = !voiceOptions }) { Text("Nastavit hlas") }
                    }
                    SettingSlider("Rychlost hlasu", "${format(settings.rate)}×", settings.rate, .75f..1.5f) { vm.updateSettings { s -> s.copy(rate = it) } }
                    if (voiceOptions) {
                        SettingSlider("Výška hlasu", "${format(settings.pitch)}×", settings.pitch, .75f..1.25f,
                            enabled = tts.pitchSupported) { vm.updateSettings { s -> s.copy(pitch = it) } }
                        Text("Pohlaví hlasu Android spolehlivě neuvádí. Vyberte jej podle ukázky; změna výšky závisí na enginu.", color = Muted,
                            style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { openTtsSettings(context) }) { Text("Otevřít nastavení TTS") }
                        TextButton(onClick = vm::refreshVoices, enabled = !session.running && !tts.speaking && !tts.loading) { Text("Obnovit nainstalované hlasy") }
                        TextButton(onClick = { openUrl(context, "https://k2-fsa.github.io/sherpa/onnx/tts/apk-engine.html") }) { Text("Bezplatný Jirka: průvodce a engine") }
                    }
                    if (tts.error != null) Text(tts.error!!, color = MaterialTheme.colorScheme.error)
                    Text(output.description, color = Muted, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (output.needsResume) item {
                Notice("Zvukový výstup se změnil nebo jej používá jiná aplikace. Další věty čekají.", "Pokračovat ve zvuku") { vm.output.resume() }
            }
            if (waiting >= 10 || session.retained > 0 || queue.items.size >= vm.queue.capacity) item {
                Notice(if (queue.items.size >= vm.queue.capacity || session.retained > 0)
                    "Fronta je plná. Příjem dalších dialogů čeká na uvolnění místa; titulky z této pauzy mohou uniknout. Přijaté věty zůstávají zachované."
                    else "Dabing zaostává za hrou. Můžete přeskočit čekající věty; rozehraná věta se dokončí.",
                    "Přeskočit čekající věty") { clearConfirm = true }
            }
            if (queue.items.any { it.stage == Stage.FAILED }) item {
                Notice(queue.items.firstOrNull { it.stage == Stage.FAILED }?.error ?: "Fronta čeká po chybě.", "Zkusit znovu", vm::retry)
            }
            if (session.message != null) item {
                Notice(session.message!!, "Rozumím") { vm.message(null) }
            }
            item {
                SectionTitle("03", "Stav překladu", if (active != null) "PŘEHRÁVÁM" else "ČEKÁM")
                Spacer(Modifier.height(12.dp))
                PanelCard {
                    Row(Modifier.fillMaxWidth()) {
                        Metric("VE FRONTĚ", waiting.toString(), Modifier.weight(1f))
                        Metric("STÁŘÍ ČELA", "${format(age)} s", Modifier.weight(1f))
                        Metric("POSLEDNÍ LATENCE", "${format(diagnostic.actualLatencyMs / 1000f)} s", Modifier.weight(1f))
                    }
                    if (queue.initialBufferRemainingMs > 0) Text("Počáteční rezerva: ${format(queue.initialBufferRemainingMs / 1000f)} s", color = Lime)
                    HorizontalDivider(color = Outline)
                    Text("EN · POSLEDNÍ OCR", color = Muted, style = MaterialTheme.typography.labelSmall)
                    Text(session.latestEnglish.ifBlank { "Zde se zobrazí anglické titulky z kamery." }, color = Muted)
                    Text(if (active != null) "CS · PRÁVĚ ZNÍ" else "CS · POSLEDNÍ PŘEKLAD", color = Lime, style = MaterialTheme.typography.labelSmall)
                    Text(displayed?.czech ?: "Váš český dabing začne po rozpoznání celé věty.", style = MaterialTheme.typography.titleLarge,
                        lineHeight = 28.sp)
                    GoogleAttribution()
                    Text("Latence = čas od prvního zachycení titulku do potvrzeného začátku TTS (bez dodatečného zpoždění Bluetooth). Stáří čela = aktuální čekání nejstarší věty.",
                        color = Muted, style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = vm::replay, enabled = queue.lastSpoken != null && (session.running || !tts.speaking),
                            modifier = Modifier.weight(1f)) { Icon(PrimeIcons.Replay, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Zopakovat") }
                        OutlinedButton(onClick = { clearConfirm = true }, enabled = waiting > 0,
                            modifier = Modifier.weight(1f)) { Icon(PrimeIcons.ClearAll, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Vyprázdnit") }
                    }
                }
            }
            item {
                TextButton(onClick = { showDiagnostics = !showDiagnostics }) {
                    Icon(PrimeIcons.Tune, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Diagnostika a slovník jmen")
                }
                if (showDiagnostics) PanelCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Test OCR – celý obraz bez filtrů")
                            Text("Bez výřezu, úprav kontrastu, filtru změn, slučování a překladu. Lze spustit i bez modelu a hlasu. Měňte při zastavení.", color = Muted)
                        }
                        Switch(session.ocrDiagnostic, vm::ocrDiagnostic, enabled = !session.running)
                    }
                    Text("KAMERA: ${if (!camera.active) "vypnuta" else if (camera.lastFrameAt == 0L || session.now - camera.lastFrameAt > 3000) "čeká na snímky" else "snímky přicházejí"}", color = Lime)
                    Text("Snímků ${camera.received} · OCR analýz ${camera.analyzed} · znaků ${camera.characters}", color = Muted)
                    Text(camera.frameInfo, color = Muted)
                    Text("OCR: ${camera.lastError ?: camera.status}", color = if (camera.lastError != null) MaterialTheme.colorScheme.error else Muted)
                    Text("Poslední přeskočení: ${camera.skipReason.ifBlank { "žádné" }}", color = Muted)
                    Text("SUROVÝ TEXT: ${camera.rawText.ifBlank { "(zatím žádný text)" }}")
                    Text("PŘEKLAD: ${if (queue.items.any { it.stage == Stage.FAILED && it.czech.isBlank() }) "chyba – fronta čeká" else model.message}", color = Muted)
                    Text("TTS: ${tts.error ?: if (tts.speaking) "přehrává" else if (tts.selected.isNotBlank()) "připraveno" else "chybí český hlas"}", color = Muted)
                    Text("OCR ${diagnostic.ocrMs} ms · překlad ${diagnostic.translationMs} ms · příprava TTS ${diagnostic.ttsPreparationMs} ms", color = Muted)
                    Text("Zachyceno ${diagnostic.captured} · duplicity ${diagnostic.duplicates + queue.duplicates} · přehráno ${queue.spoken} · přeskočeno ${queue.skipped}", color = Muted)
                    Text("Analýzy ${diagnostic.ocrCount} · vynechané analýzy ${diagnostic.skippedFrames} · jistota ${(diagnostic.confidence * 100).roundToInt()} % (odhad)", color = Muted)
                    Text("Fúze: ${diagnostic.fusion} · teplotní stupeň ${camera.thermal}", color = Muted)
                    TextButton(onClick = { vm.download(wifiOnly) }, enabled = !session.running && !model.downloading) { Text("Znovu připravit offline překladač") }
                    Text("Kontext v paměti: 8 dialogů; ověřená lokální AI dostává nejvýše 2 předchozí dialogy. ML Kit konverzační kontext nepodporuje.", color = Muted)
                    OutlinedTextField(value = settings.names, onValueChange = { names -> vm.updateSettings { it.copy(names = names.take(500)) } },
                        label = { Text("Jména postav oddělená čárkou") }, placeholder = { Text("Lucia, Jason") }, modifier = Modifier.fillMaxWidth())
                    Text("Zadaná jména se při překladu chrání. Slang a vztahy mezi postavami nemusí lokální překladač pochopit správně.", color = Muted)
                    diagnostic.errors.takeLast(4).forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
    if (clearConfirm) AlertDialog(onDismissRequest = { clearConfirm = false }, title = { Text("Přeskočit čekající dialogy?") },
        text = { Text("Přeskočí se $waiting nepřehraných vět. Právě přehrávaná věta zůstane nedotčená.") },
        confirmButton = { TextButton(onClick = { vm.skipWaiting(); clearConfirm = false }) { Text("Přeskočit čekající") } },
        dismissButton = { TextButton(onClick = { clearConfirm = false }) { Text("Zachovat frontu") } })
    if (showHelp) AlertDialog(onDismissRequest = { showHelp = false }, title = { Text("Připraveno na vaši hru") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("1. Před první hrou stáhněte překladový model a český offline hlas.\n\n2. Na televizi zapněte anglické titulky. Telefon opřete nebo upevněte před obrazovku.\n\n3. Spusťte překlad a označte oblast všech řádků titulků. Klepnutím zaostříte.\n\n4. Vyberte hlas a počáteční rezervu před další relací. Bluetooth nastavte běžně v Androidu.")
            Text("Jirka je dostupný jako Piper model přes sherpa-onnx TTS engine. Na POCO vyberte variantu arm64-v8a, cs_CZ-jirka-medium. Po instalaci obnovte seznam hlasů. Android obecně neuvádí pohlaví hlasů.")
            Text("Kamera i mikrofon se při odchodu z aplikace vypnou. Rozehraná věta se dokončí; čekající fronta zůstává pouze v paměti do ukončení procesu. Video, snímky ani zvuk se neukládají.")
            Text("Překlady zajišťuje Google Translate prostřednictvím offline ML Kit. Výsledek je strojový a může chybovat, zejména u slangu. Nejde o oficiální herní dabing.")
            TextButton(onClick = { openUrl(context, "https://cloud.google.com/translate") }) { Text("Informace o Google Translate") }
            TextButton(onClick = { openUrl(context, "https://k2-fsa.github.io/sherpa/onnx/tts/apk-engine.html") }) { Text("Stáhnout bezplatný TTS engine Jirka") }
            TextButton(onClick = {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                context.startActivity(intent)
            }) { Text("Oprávnění aplikace") }
        } }, confirmButton = { TextButton(onClick = { showHelp = false }) { Text("Rozumím") } })
}

@Composable private fun PanelCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = Panel, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, Outline), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}
@Composable private fun Tag(label: String, background: Color, foreground: Color = Color.White) {
    Text(label, Modifier.clip(RoundedCornerShape(6.dp)).background(background).padding(horizontal = 9.dp, vertical = 6.dp),
        color = foreground, style = MaterialTheme.typography.labelSmall)
}
@Composable private fun SectionTitle(number: String, title: String, status: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(number, color = Lime, style = MaterialTheme.typography.labelSmall)
        Text(title, Modifier.weight(1f).padding(start = 10.dp), style = MaterialTheme.typography.titleLarge)
        status?.let { Text(it, color = Muted, style = MaterialTheme.typography.labelSmall) }
    }
}
@Composable private fun SettingSlider(title: String, label: String, value: Float, range: ClosedFloatingPointRange<Float>,
                                      steps: Int = 0, enabled: Boolean = true, change: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Text(label, color = Lime, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        }
        Slider(value, change, enabled = enabled, valueRange = range, steps = steps)
    }
}
@Composable private fun ToggleRow(title: String, description: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title); Text(description, color = Muted, style = MaterialTheme.typography.bodySmall) }
        Switch(checked, change)
    }
}
@Composable private fun Metric(label: String, value: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 23.sp)
        Text(label, color = Muted, fontSize = 9.sp, letterSpacing = .6.sp)
    }
}
@Composable private fun Notice(message: String, action: String, onAction: () -> Unit) {
    PanelCard { Text(message); TextButton(onClick = onAction) { Text(action) } }
}
@Composable private fun GoogleAttribution() {
    val context = LocalContext.current
    // Official attribution graphic is included unchanged in drawable-nodpi.
    Image(androidx.compose.ui.res.painterResource(cz.primevoice.R.drawable.google_translate_attribution),
        "powered by Google Translate", Modifier.height(20.dp).clickable { openUrl(context, "https://translate.google.com") })
}
private fun format(value: Float) = String.format(Locale.forLanguageTag("cs"), "%.1f", value)
private fun openUrl(context: android.content.Context, url: String) {
    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) { }
}
private fun openTtsSettings(context: android.content.Context) {
    try { context.startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
    catch (_: Exception) { try { context.startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Exception) { } }
}
