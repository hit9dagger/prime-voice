package cz.primevoice.camera

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cz.primevoice.MainActivity
import cz.primevoice.PrimeVoiceViewModel
import cz.primevoice.core.*
import cz.primevoice.data.Settings
import cz.primevoice.speech.*
import cz.primevoice.translation.AdaptiveTranslationEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

private const val WEATHER = "The weather is beautiful today."

@RunWith(AndroidJUnit4::class)
class CameraPipelineTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext<Application>()
    private val full = Settings(region = Region(0f, 0f, 1f, 1f))

    @Test fun yuvPlanesReadExactEnglishForEveryRotationAndBothBudgets() = runBlocking {
        val processor = OCRProcessor()
        try {
            for (width in listOf(960, 1100)) for (rotation in listOf(0, 90, 180, 270)) {
                val frame = YuvTextFrame(WEATHER, rotation, pixelStep = 2, bufferOffset = 13)
                val crop = processor.crop(frame, full.region, false, width)
                try {
                    val result = processor.recognize(crop, 0)
                    assertEquals("rotation=$rotation width=$width", WEATHER, result.reading.text)
                } finally { crop.bitmap.recycle(); frame.close() }
                assertEquals(1, frame.closes.get())
            }
        } finally { processor.close() }
    }

    @Test fun analyzerSurvivesSkippedFramesErrorsAndTranslationContention() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val gate = InferenceGate()
        val readings = Channel<OcrResult>(Channel.UNLIMITED)
        var enabled = true
        val manager = CameraCaptureManager(context, scope, { full }, { enabled }, { readings.trySend(it) }, {},
            { CaptureBudget(1280, 720, 960, 400, 1200) }, gate, {}, { true })
        val token = manager.beginSession()
        suspend fun submit(frame: YuvTextFrame) {
            manager.analyze(frame, token)
            withTimeout(30_000) { while (frame.closes.get() == 0) delay(10) }
            assertEquals(1, frame.closes.get())
        }
        try {
            // These two branches deadlocked the 2.0 analyzer before reaching any OCR.
            enabled = false; submit(YuvTextFrame(WEATHER)); enabled = true
            gate.run { submit(YuvTextFrame(WEATHER)) }
            submit(YuvTextFrame(WEATHER))
            assertEquals(WEATHER, withTimeout(30_000) { readings.receive() }.reading.text)
            repeat(3) { submit(YuvTextFrame(WEATHER)) } // cadence-skipped frames
            delay(450)
            submit(YuvTextFrame(WEATHER, invalidFormat = true))
            assertTrue(manager.state.value.lastError.orEmpty().contains("YUV_420_888"))
            delay(450)
            submit(YuvTextFrame(WEATHER))
            withTimeout(1000) { gate.run { } } // failed/skipped OCR must never block translation
            assertEquals(WEATHER, manager.state.value.rawText)
            assertTrue(manager.state.value.analyzed >= 2)
            assertEquals(WEATHER.length, manager.state.value.characters)
            assertNull(manager.state.value.lastError)
        } finally { withContext(Dispatchers.Main) { manager.close() }; scope.cancel(); readings.close() }
    }

    @Test fun liveCameraXFeedsAnalyzerAndStopsWithoutModelsBeingRequired() = runBlocking {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.executeShellCommand("pm grant cz.primevoice android.permission.CAMERA").use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        }
        var vm: PrimeVoiceViewModel? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                vm = ViewModelProvider(it)[PrimeVoiceViewModel::class.java]
                vm!!.ocrDiagnostic(true); vm!!.start()
            }
            val model = vm!!
            try {
                withTimeout(40_000) { model.camera.state.first { it.received >= 10 && it.analyzed >= 3 } }
            } catch (e: TimeoutCancellationException) {
                throw AssertionError("Camera did not start: ${model.camera.state.value}; session=${model.session.value}", e)
            }
            // Camera remains attached and analyzing when its preview scrolls out of sight.
            val metrics = context.resources.displayMetrics
            repeat(5) {
                val command = "input swipe ${metrics.widthPixels / 2} ${metrics.heightPixels * 2 / 3} ${metrics.widthPixels / 2} ${metrics.heightPixels / 4} 200"
                automation.executeShellCommand(command).use { descriptor ->
                    java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
                }
            }
            val before = model.camera.state.value.analyzed
            withTimeout(15_000) { model.camera.state.first { it.analyzed >= before + 3 } }
            assertTrue(model.session.value.running)
            assertNull(model.camera.state.value.lastError)
            scenario.onActivity { model.stop() }
            delay(500)
            val stopped = model.camera.state.value.received
            delay(800)
            assertEquals(stopped, model.camera.state.value.received)
            assertFalse(model.camera.state.value.active)
        }
    }

    @Test fun offlineRealOcrTranslationAndCzechTtsCompleteInOrder() = runBlocking {
        // CI downloads ML Kit and installs the real Jirka engine first, then disables networking.
        withTimeout(15_000) {
            while (context.getSystemService(ConnectivityManager::class.java).activeNetwork != null) delay(100)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val memory = DialogueContextManager()
        val gate = InferenceGate()
        val translator = AdaptiveTranslationEngine(context, scope, memory, { emptyList() }, ExecutionMode.LIGHT, gate)
        val output = withContext(Dispatchers.Main) { AudioOutputManager(context) {} }
        val tts = CzechTTSManager(context, output, { full }) {}
        val processor = OCRProcessor()
        try {
            withContext(Dispatchers.Main) { translator.initialize(); tts.discover() }
            assertTrue(translator.state.value.ready)
            assertTrue("No real offline Czech engine: ${tts.state.value.error}", tts.state.value.selected.isNotBlank())
            val installedJirka = tts.state.value.voices.firstOrNull { it.engine == "com.k2fsa.sherpa.onnx.tts.engine" }
            assertNotNull("The checksum-pinned real Jirka TTS APK must expose a Czech voice", installedJirka)
            withContext(Dispatchers.Main) { tts.select(installedJirka!!.id) }
            assertEquals(installedJirka!!.id, tts.state.value.selected)
            val queue = TranslationQueueManager(scope, translator, tts, SystemClock::elapsedRealtime,
                translationTimeoutMs = 60_000, translationParallelism = 1)
            queue.start(0)
            val detector = SubtitleStabilityDetector()
            for ((index, text) in listOf(WEATHER, "Where is Jason?").withIndex()) {
                val frame = YuvTextFrame(text)
                val crop = processor.crop(frame, full.region, false, 960)
                val result = try { processor.recognize(crop, 0) } finally { crop.bitmap.recycle(); frame.close() }
                assertEquals(text, result.reading.text)
                val completed = listOf(0L, 450L, 900L).flatMap {
                    detector.observe(result.reading.copy(at = index * 3000L + it))
                }
                assertEquals(1, completed.size)
                assertTrue(queue.offer(completed.single()))
            }
            val finished = withTimeout(120_000) {
                queue.state.first { it.spoken == 2 || it.items.any { row -> row.stage == Stage.FAILED } }
            }
            assertEquals("${finished.items}", 2, finished.spoken)
            assertTrue(finished.items.isEmpty())
            val translations = memory.snapshot()
            println("REAL_OFFLINE_PIPELINE: $translations; PCM bytes=${tts.state.value.audioBytes}; voice=${tts.state.value.selected}")
            assertEquals(listOf(WEATHER, "Where is Jason?"), translations.map { it.english })
            assertTrue(translations[0].czech.lowercase().contains("počasí"))
            assertTrue(translations[0].czech.lowercase().contains("dnes"))
            assertTrue(translations[1].czech.lowercase().contains("kde"))
            assertTrue(translations[1].czech.contains("Jason"))
            assertTrue("Engine did not produce audio PCM", tts.state.value.audioBytes > 1000)
            // Unsupported/unprepared AI modes must preserve a working LIGHT fallback.
            for (mode in listOf(ExecutionMode.STANDARD, ExecutionMode.ULTRA, ExecutionMode.LIGHT)) {
                withContext(Dispatchers.Main) { translator.select(mode) }
                assertEquals(ExecutionMode.LIGHT, translator.execution.value.effective)
                assertTrue(translator.translate("Where is Jason?").lowercase().contains("kde"))
            }
        } finally {
            processor.close()
            withContext(Dispatchers.Main) { tts.close(); output.close(); translator.close() }
            scope.cancel()
        }
    }
}
