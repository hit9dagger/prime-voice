package cz.primevoice

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cz.primevoice.camera.*
import cz.primevoice.core.*
import cz.primevoice.data.*
import cz.primevoice.speech.*
import cz.primevoice.translation.AdaptiveTranslationEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SessionState(val running: Boolean = false, val latestEnglish: String = "",
                        val message: String? = null, val retained: Int = 0, val now: Long = 0,
                        val calibrating: Boolean = false, val ocrDiagnostic: Boolean = false)

class PrimeVoiceViewModel(application: Application) : AndroidViewModel(application) {
    val settings = SettingsRepository(application)
    val diagnostics = DiagnosticsManager()
    val contextMemory = DialogueContextManager()
    private val gate = InferenceGate()
    val translation = AdaptiveTranslationEngine(application, viewModelScope, contextMemory,
        { settings.state.value.names.split(',') }, settings.state.value.mode, gate)
    private val mutable = MutableStateFlow(SessionState())
    val session = mutable.asStateFlow()
    private val detector = SubtitleStabilityDetector()
    private val detectorMutex = Mutex()
    private val fusion = MultimodalFusionEngine()
    private val readings = Channel<OcrResult>(Channel.CONFLATED)
    private val retained = ArrayDeque<Subtitle>()
    val output: AudioOutputManager = AudioOutputManager(application) { allowed -> viewModelScope.launch { queue.allowOutput(allowed) } }
    val tts: CzechTTSManager = CzechTTSManager(application, output, { settings.state.value }) { elapsed ->
        val active = queue.state.value.items.firstOrNull { it.stage == Stage.SPEAKING }
        diagnostics.update { it.copy(ttsPreparationMs = elapsed,
            actualLatencyMs = active?.let { item -> SystemClock.elapsedRealtime() - item.subtitle.capturedAt } ?: it.actualLatencyMs) }
    }
    val queue: TranslationQueueManager = TranslationQueueManager(viewModelScope, translation, tts, SystemClock::elapsedRealtime,
        translationTimeoutMs = 30_000, translationParallelism = 1)
    val asr = SpeechRecognitionProcessor(application) { fusion.add(it) }
    val camera = CameraCaptureManager(application, viewModelScope, { settings.state.value }, {
        mutable.value.running && !mutable.value.calibrating &&
            (mutable.value.ocrDiagnostic || !pipelineReady() ||
                (mutable.value.retained == 0 && queue.state.value.items.size < queue.capacity))
    }, { readings.trySend(it) }, { diagnostics.error(it); mutable.update { s -> s.copy(message = it) } },
        { val e = translation.execution.value; ExecutionPolicy.capture(e.effective, e.resources) }, gate,
        { memoryPressure() }, { mutable.value.ocrDiagnostic })

    init {
        viewModelScope.launch { translation.initialize() }
        viewModelScope.launch { tts.discover() }
        viewModelScope.launch {
            for (result in readings) {
                if (!mutable.value.running) continue
                mutable.update { it.copy(latestEnglish = result.reading.text) }
                // OCR always runs independently of translation models and voices. Diagnostic text
                // is shown verbatim, without fusion, completeness or duplicate filtering.
                if (mutable.value.ocrDiagnostic || !pipelineReady()) continue
                if (result.reading.text.isNotBlank()) asr.subtitlesVisible()
                val fused = fusion.fuse(result.reading)
                mutable.update { it.copy(latestEnglish = result.reading.text) }
                // CPU text comparisons stay off the UI thread; all detector ownership is this coroutine.
                val completed = withContext(Dispatchers.Default) {
                    detectorMutex.withLock {
                        detector.observe(result.reading.copy(text = fused.text, confidence = fused.confidence), fused.alignedEnd)
                    }
                }
                if (!mutable.value.running || mutable.value.calibrating) continue
                diagnostics.update { it.copy(ocrMs = result.elapsedMs, ocrCount = it.ocrCount + 1,
                    confidence = fused.confidence, fusion = fused.explanation, duplicates = detector.duplicates,
                    captured = it.captured + completed.size) }
                completed.forEach { retained.addLast(it) }
                drainRetained()
            }
        }
        viewModelScope.launch {
            tts.state.map { it.speaking }.distinctUntilChanged().collect { asr.suppressEcho(it) }
        }
        viewModelScope.launch { gate.heavy.collect { asr.suppressResources(it) } }
        viewModelScope.launch {
            while (isActive) { delay(5000); translation.refresh() }
        }
        viewModelScope.launch {
            camera.state.map { it.thermal }.distinctUntilChanged().collect { level ->
                if (level >= PowerManager.THERMAL_STATUS_CRITICAL && mutable.value.running) {
                    stop(); mutable.update { it.copy(message = "Telefon je příliš horký. Překlad byl pozastaven; čekající věty zůstávají ve frontě.") }
                } else if (level >= PowerManager.THERMAL_STATUS_SEVERE) {
                    asr.enable(false)
                    translation.fallback("Telefon se zahřívá. Náročná AI byla uvolněna; pokračuje LIGHT.")
                }
            }
        }
        viewModelScope.launch {
            while (isActive) {
                mutable.update { it.copy(now = SystemClock.elapsedRealtime()) }
                if (mutable.value.running && !mutable.value.ocrDiagnostic && pipelineReady()) {
                    if (!queue.state.value.enabled) queue.start(settings.state.value.delaySeconds)
                    drainRetained()
                }
                val q = queue.state.value
                diagnostics.update { it.copy(translationMs = q.lastReady?.translationMs ?: 0,
                    skippedFrames = camera.state.value.skipped) }
                delay(if (ExecutionPolicy.economical(translation.execution.value.resources)) 750 else 350)
            }
        }
    }
    private fun pipelineReady() = translation.state.value.ready && !translation.execution.value.busy &&
        tts.state.value.selected.isNotBlank() && !tts.state.value.loading

    private suspend fun drainRetained() {
        if (!pipelineReady() || mutable.value.ocrDiagnostic) return
        while (retained.isNotEmpty()) {
            if (!queue.offer(retained.first())) break
            retained.removeFirst()
        }
        mutable.update { it.copy(retained = retained.size) }
    }
    fun download(wifiOnly: Boolean) { viewModelScope.launch { translation.download(wifiOnly) } }
    fun selectMode(mode: ExecutionMode) {
        if (mutable.value.running || translation.execution.value.busy) return
        settings.update { it.copy(mode = mode) }; translation.select(mode)
    }
    fun memoryPressure() {
        translation.memoryPressure(); asr.enable(false)
        diagnostics.error("Paměťový tlak: AI uvolněna, OCR omezeno, fronta zachována.")
    }
    fun background() { stop(); translation.background() }
    fun refreshVoices() { if (!mutable.value.running && !tts.state.value.speaking) viewModelScope.launch { tts.discover() } }
    fun selectVoice(id: String) {
        if (tts.state.value.speaking || mutable.value.running) return
        val choice = tts.state.value.voices.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            tts.select(id)
            if (tts.state.value.selected == id) settings.update { it.copy(engine = choice.engine, voice = choice.name) }
        }
    }
    fun previewVoice() {
        if (mutable.value.running || tts.state.value.speaking) return
        viewModelScope.launch {
            try { tts.speak("Ahoj, tady PRIME VOICE. Český dabing je připraven. Můžeme vyrazit.") }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(message = e.message) } }
        }
    }
    fun start() {
        if (camera.state.value.thermal >= PowerManager.THERMAL_STATUS_CRITICAL) {
            mutable.update { it.copy(message = "Nechte telefon vychladnout.") }; return
        }
        if (ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            mutable.update { it.copy(message = "Pro překlad je potřeba povolit kameru.") }; return
        }
        viewModelScope.launch {
            detectorMutex.withLock { detector.reset() }
            if (pipelineReady() && !mutable.value.ocrDiagnostic) queue.start(settings.state.value.delaySeconds) else queue.pause()
            mutable.update { it.copy(running = true, message = if (!pipelineReady())
                "OCR běží samostatně. Pro překlad a dabing připravte offline překladač a český hlas. Do té doby se text pouze zobrazuje." else null) }
            applyMicrophone()
        }
    }
    fun ocrDiagnostic(enabled: Boolean) {
        if (mutable.value.running) return
        mutable.update { it.copy(ocrDiagnostic = enabled) }
    }
    fun stop() {
        mutable.update { it.copy(running = false, calibrating = false) }
        camera.stop(); asr.enable(false); fusion.clear()
        viewModelScope.launch { queue.pause() }
    }
    fun microphone(enabled: Boolean) {
        settings.update { it.copy(microphone = enabled) }
        fusion.clear(); applyMicrophone()
    }
    private fun applyMicrophone() {
        val granted = ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        asr.enable(mutable.value.running && !mutable.value.ocrDiagnostic && settings.state.value.microphone && granted &&
            camera.state.value.thermal < PowerManager.THERMAL_STATUS_SEVERE)
    }
    fun updateSettings(block: (Settings) -> Settings) { settings.update(block); camera.applySettings() }
    fun calibrate(enabled: Boolean) {
        mutable.update { it.copy(calibrating = enabled) }
        if (!enabled) viewModelScope.launch { detectorMutex.withLock { detector.reset() } }
    }
    fun skipWaiting() {
        viewModelScope.launch { retained.clear(); mutable.update { it.copy(retained = 0) }; queue.skipWaiting() }
    }
    fun retry() { viewModelScope.launch { queue.retryFailed() } }
    fun replay() {
        viewModelScope.launch {
            if (mutable.value.running) queue.replayLast()
            else {
                val text = queue.state.value.lastSpoken?.czech ?: return@launch
                try { tts.speak(text) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { message(e.message ?: "Opakování se nezdařilo") }
            }
        }
    }
    fun message(text: String?) { mutable.update { it.copy(message = text) } }
    override fun onCleared() {
        camera.close(); asr.close(); tts.close(); output.close(); translation.close(); readings.close()
        super.onCleared()
    }
}
