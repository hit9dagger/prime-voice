package cz.primevoice.camera

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.util.Rational
import android.util.Size
import android.view.Surface
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import cz.primevoice.data.Settings
import cz.primevoice.core.CaptureBudget
import cz.primevoice.core.InferenceGate
import cz.primevoice.core.FrameAdmission
import cz.primevoice.core.Region
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.Executors
import kotlin.math.abs

data class CameraState(val active: Boolean = false, val status: String = "Kamera je vypnutá",
                       val zoomMax: Float = 4f, val exposureMin: Int = 0, val exposureMax: Int = 0,
                       val thermal: Int = 0, val ocrMs: Long = 0, val skipped: Int = 0,
                       val received: Long = 0, val analyzed: Long = 0, val characters: Int = 0,
                       val rawText: String = "", val frameInfo: String = "", val lastError: String? = null,
                       val skipReason: String = "", val lastFrameAt: Long = 0)

class CameraCaptureManager(private val context: Context, private val scope: CoroutineScope,
                           private val settings: () -> Settings, private val canAnalyze: () -> Boolean,
                           private val onOcr: (OcrResult) -> Unit, private val onError: (String) -> Unit,
                           private val budget: () -> CaptureBudget, private val gate: InferenceGate,
                           private val onMemoryPressure: () -> Unit,
                           private val diagnosticMode: () -> Boolean = { false }) {
    private val processor = OCRProcessor()
    private val executor = Executors.newSingleThreadExecutor()
    private val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val admission = FrameAdmission(gate)
    private val mutable = MutableStateFlow(CameraState())
    val state = mutable.asStateFlow()
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var analysis: ImageAnalysis? = null
    private var preview: Preview? = null
    @Volatile private var generation = 0
    @Volatile private var running = false
    private var lastOcr = 0L
    private var lastText = ""
    private var lastTextAt = 0L
    private var unchanged = 0
    private var signature: IntArray? = null
    private val power = context.getSystemService(PowerManager::class.java)
    private val thermalListener = PowerManager.OnThermalStatusChangedListener { value ->
        mutable.update { it.copy(thermal = value) }
        if (value >= PowerManager.THERMAL_STATUS_CRITICAL) onError("Telefon je příliš horký. Zastavte překlad a nechte jej vychladnout.")
    }
    init { power.addThermalStatusListener(ContextCompat.getMainExecutor(context), thermalListener) }

    fun start(owner: LifecycleOwner, view: PreviewView) {
        if (running) return
        val token = beginSession()
        view.scaleType = PreviewView.ScaleType.FILL_CENTER
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (!running || generation != token) return@addListener
            try {
                val p = future.get(); provider = p
                val rotation = view.display?.rotation ?: Surface.ROTATION_0
                val limit = budget()
                val selector = ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy(Size(limit.width, limit.height), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build()
                val pre = Preview.Builder().setResolutionSelector(selector).setTargetRotation(rotation).build()
                val ana = ImageAnalysis.Builder().setResolutionSelector(selector).setTargetRotation(rotation)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                pre.setSurfaceProvider(view.surfaceProvider)
                val viewport = ViewPort.Builder(Rational(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1)), rotation)
                    .setScaleType(ViewPort.FILL_CENTER).build()
                val group = UseCaseGroup.Builder().setViewPort(viewport).addUseCase(pre).addUseCase(ana).build()
                camera = p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, group)
                android.util.Log.i("PrimeVoiceCamera", "CameraX bound: preview ${view.width}x${view.height}, rotation $rotation")
                preview = pre; analysis = ana
                ana.setAnalyzer(executor) { frame -> analyze(frame, token) }
                val info = camera!!.cameraInfo
                mutable.update { it.copy(active = true, status = "Hledám titulky…",
                    zoomMax = minOf(8f, info.zoomState.value?.maxZoomRatio ?: 4f),
                    exposureMin = info.exposureState.exposureCompensationRange.lower,
                    exposureMax = info.exposureState.exposureCompensationRange.upper) }
                applySettings()
                focus(view, view.width * .5f, view.height * .78f)
            } catch (e: Exception) {
                val message = "Kamera: ${e.javaClass.simpleName}: ${e.localizedMessage}"
                android.util.Log.e("PrimeVoiceCamera", message, e)
                running = false; mutable.update { it.copy(active = false, status = "Kameru nelze spustit", lastError = message) }
                onError(message)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /** Shared entry point for CameraX and instrumented ImageProxy tests. */
    internal fun beginSession(): Int {
        running = true
        val token = ++generation
        admission.resetCadence()
        lastTextAt = SystemClock.elapsedRealtime(); lastOcr = 0; signature = null; unchanged = 0; lastText = ""
        mutable.update { CameraState(thermal = it.thermal, status = "Spouštím analýzu…") }
        return token
    }

    internal fun analyze(frame: ImageProxy, token: Int) {
        val now = SystemClock.elapsedRealtime(); val thermal = mutable.value.thermal; val limit = budget()
        val interval = when {
            thermal >= PowerManager.THERMAL_STATUS_CRITICAL -> Long.MAX_VALUE
            thermal >= PowerManager.THERMAL_STATUS_SEVERE -> 1100L
            thermal >= PowerManager.THERMAL_STATUS_MODERATE -> 550L
            else -> limit.intervalMs
        }
        mutable.update { it.copy(received = it.received + 1, lastFrameAt = now) }
        val lease = admission.acquire(now, interval, running && token == generation && canAnalyze())
        if (lease == null) {
            mutable.update { it.copy(skipped = it.skipped + 1, skipReason = admission.skippedBecause) }
            frame.close(); return
        }
        val diagnostic = diagnosticMode()
        val job = workerScope.launch {
            var crop: Crop? = null
            try {
                mutable.update { it.copy(frameInfo = "${frame.width}×${frame.height} · formát ${frame.format} · rotace ${frame.imageInfo.rotationDegrees}°") }
                val s = settings()
                crop = processor.crop(frame, if (diagnostic) Region(0f, 0f, 1f, 1f) else s.region,
                    !diagnostic && s.enhance, limit.cropWidth)
                val previous = signature
                val change = if (previous == null) 100f else crop.signature.indices.sumOf { abs(crop.signature[it] - previous[it]) }.toFloat() / crop.signature.size
                signature = crop.signature
                val nextOcr = if (unchanged >= 5 && change < 3f) maxOf(limit.unchangedIntervalMs, interval) else interval
                if (!diagnostic && now - lastOcr < nextOcr) {
                    mutable.update { it.copy(skipped = it.skipped + 1, skipReason = "nezměněný obraz") }
                    return@launch
                }
                lastOcr = now
                mutable.update { it.copy(analyzed = it.analyzed + 1, skipReason = "") }
                val result = withContext(NonCancellable) { processor.recognize(crop, now) }
                if (running && generation == token) {
                    if (result.reading.text == lastText) unchanged++ else unchanged = 0
                    lastText = result.reading.text
                    if (lastText.isNotBlank()) lastTextAt = now
                    mutable.update { it.copy(ocrMs = result.elapsedMs, rawText = result.reading.text,
                        characters = result.reading.text.length, lastError = null, status = when {
                        thermal >= PowerManager.THERMAL_STATUS_SEVERE -> "Telefon je horký • pomalejší snímání"
                        result.blurry -> "Obraz může být rozmazaný • klepněte pro zaostření"
                        now - lastTextAt > 5000 -> "Titulky nejsou vidět • zkontrolujte výřez"
                        lastText.isNotBlank() -> "Čtu oblast titulků"
                        else -> "Čekám na titulky"
                    }) }
                    withContext(Dispatchers.Main.immediate) { if (running && generation == token) onOcr(result) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: OutOfMemoryError) {
                mutable.update { it.copy(lastError = "OCR: nedostatek paměti") }
                withContext(Dispatchers.Main.immediate) { onMemoryPressure() }
            }
            catch (e: Exception) {
                val message = "OCR: ${e.javaClass.simpleName}: ${e.localizedMessage}"
                android.util.Log.e("PrimeVoiceOCR", message, e)
                mutable.update { it.copy(lastError = message) }
                withContext(Dispatchers.Main.immediate) { if (running && generation == token) onError(message) }
            } finally { crop?.bitmap?.recycle() }
        }
        // Also runs if the coroutine was cancelled before its body could start.
        job.invokeOnCompletion { try { frame.close() } finally { lease.close() } }
    }

    fun applySettings() {
        val c = camera ?: return; val s = settings(); val info = c.cameraInfo
        val zoom = info.zoomState.value
        c.cameraControl.setZoomRatio(s.zoom.coerceIn(zoom?.minZoomRatio ?: 1f, zoom?.maxZoomRatio ?: 4f))
        if (info.exposureState.isExposureCompensationSupported)
            c.cameraControl.setExposureCompensationIndex(s.exposure.coerceIn(info.exposureState.exposureCompensationRange.lower,
                info.exposureState.exposureCompensationRange.upper))
    }
    fun focus(view: PreviewView, x: Float, y: Float) {
        val point = view.meteringPointFactory.createPoint(x, y)
        camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE).setAutoCancelDuration(5, java.util.concurrent.TimeUnit.SECONDS).build())
    }
    fun stop() {
        running = false; generation++
        analysis?.clearAnalyzer()
        val cases = listOfNotNull(preview, analysis).toTypedArray()
        if (cases.isNotEmpty()) provider?.unbind(*cases)
        analysis = null; preview = null; camera = null
        mutable.update { it.copy(active = false, status = "Kamera je vypnutá") }
    }
    fun close() {
        stop(); power.removeThermalStatusListener(thermalListener); executor.shutdown()
        // Avoid closing the recognizer while a submitted ML task still owns its image.
        workerScope.launch { while (admission.inFlight) delay(50); processor.close(); workerScope.cancel() }
    }
}
