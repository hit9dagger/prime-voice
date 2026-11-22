package cz.primevoice

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import cz.primevoice.engine.Translation
import kotlinx.coroutines.*
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Camera subtitle mode. The microphone-based mode remains available separately. */
class CameraActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val translator = Translation()
    private val worker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private var provider: ProcessCameraProvider? = null
    private var tts: TextToSpeech? = null
    private var started = false
    private var modelReady = false
    private var lastFrame = 0L
    private var lastCandidate = ""
    private var candidateSince = 0L
    private var lastSeen = 0L
    private var lastSpoken = ""
    private val recent = ArrayDeque<String>()
    private var delaySeconds = 5
    private var roiFraction = 0.30f
    private var maleVoice: android.speech.tts.Voice? = null
    private var femaleVoice: android.speech.tts.Voice? = null
    private var availableVoices = emptyList<android.speech.tts.Voice>()
    private lateinit var preview: PreviewView
    private lateinit var status: TextView
    private lateinit var english: TextView
    private lateinit var czech: TextView
    private lateinit var startButton: Button
    private lateinit var voicesA: Spinner
    private lateinit var voicesB: Spinner
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else status.text = "Přístup ke kameře nebyl povolen."
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(14, 12, 14, 12) }
        val scroll = ScrollView(this)
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
        root.addView(preview, LinearLayout.LayoutParams(-1, 0, 1f))
        scroll.addView(panel)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        status = TextView(this).apply { text = "Připravuji lokální překlad…" }
        english = TextView(this).apply { text = "EN: čekám na titulek" }
        czech = TextView(this).apply { text = "CZ: čekám na překlad" }
        startButton = Button(this).apply { text = "START"; setOnClickListener { if (started) stopCamera() else requestCamera() } }
        panel.addView(status); panel.addView(startButton)
        panel.addView(TextView(this).apply { text = "Oblast titulků: spodní část obrazu (bez HUD)" })
        panel.addView(SeekBar(this).apply {
            max = 65; progress = 20
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(b: SeekBar?, p: Int, user: Boolean) { roiFraction = (p + 10) / 100f }
                override fun onStartTrackingTouch(b: SeekBar?) {}
                override fun onStopTrackingTouch(b: SeekBar?) {}
            })
        })
        panel.addView(TextView(this).apply { text = "Zpoždění dabingu: 3–30 sekund" })
        val delayLabel = TextView(this).apply { text = "5 sekund" }
        panel.addView(delayLabel)
        panel.addView(SeekBar(this).apply {
            max = 27; progress = 2
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(b: SeekBar?, p: Int, user: Boolean) { delaySeconds = p + 3; delayLabel.text = "$delaySeconds sekund" }
                override fun onStartTrackingTouch(b: SeekBar?) {}
                override fun onStopTrackingTouch(b: SeekBar?) {}
            })
        })
        panel.addView(english); panel.addView(czech)
        panel.addView(TextView(this).apply { text = "Český hlas A (výchozí)" })
        voicesA = Spinner(this); panel.addView(voicesA)
        panel.addView(TextView(this).apply { text = "Český hlas B (volitelný)" })
        voicesB = Spinner(this); panel.addView(voicesB)
        panel.addView(TextView(this).apply { text = "Google Speech Services lze zdarma nastavit v Androidu jako TTS engine. Dva odlišné české hlasy nemusí být dostupné. Automatické rozpoznání pohlaví z titulků není spolehlivé; MVP používá hlas A." })
        setContentView(root)
        lifecycleScope.launch {
            try {
                modelReady = withContext(Dispatchers.IO) { translator.ready() }
                status.text = if (modelReady) "Překlad připraven" else "Stahuji model EN → CS (internet jen poprvé)…"
                if (!modelReady) { translator.prepare(); modelReady = true; status.text = "Překlad připraven" }
            } catch (e: Exception) { status.text = "Překlad není připraven: ${e.localizedMessage}" }
        }
    }
    override fun onInit(result: Int) {
        if (result != TextToSpeech.SUCCESS) { runOnUiThread { status.text = "Český TTS engine není dostupný" }; return }
        val engine = tts ?: return
        engine.language = Locale.forLanguageTag("cs-CZ")
        availableVoices = engine.voices.orEmpty().filter { it.locale.language == "cs" && !it.isNetworkConnectionRequired }.sortedBy { it.name }
        runOnUiThread {
            val labels = listOf("Výchozí český hlas") + availableVoices.map { it.name }
            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
            voicesA.adapter = adapter; voicesB.adapter = adapter
            voicesA.onItemSelectedListener = voiceListener { maleVoice = availableVoices.getOrNull(it - 1) }
            voicesB.onItemSelectedListener = voiceListener { femaleVoice = availableVoices.getOrNull(it - 1) }
            if (availableVoices.size < 2) Toast.makeText(this, "Dva odlišné lokální české hlasy nejsou dostupné.", Toast.LENGTH_LONG).show()
        }
    }
    private fun voiceListener(select: (Int) -> Unit) = object : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) = select(position)
        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
    }
    private fun requestCamera() {
        if (!modelReady) { status.text = "Nejdříve počkejte na překladový model."; return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else permission.launch(Manifest.permission.CAMERA)
    }
    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val p = future.get(); provider = p
                val cameraPreview = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
                val analyzer = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analyzer.setAnalyzer(worker) { frame ->
                    val now = SystemClock.elapsedRealtime()
                    if (!started || now - lastFrame < 300 || !busy.compareAndSet(false, true)) { frame.close(); return@setAnalyzer }
                    lastFrame = now
                    val media = frame.image
                    if (media == null) { busy.set(false); frame.close(); return@setAnalyzer }
                    val input = InputImage.fromMediaImage(media, frame.imageInfo.rotationDegrees)
                    recognizer.process(input).addOnSuccessListener { result ->
                        val height = if (frame.imageInfo.rotationDegrees % 180 == 0) frame.height else frame.width
                        val threshold = height * (1f - roiFraction)
                        val lines = result.textBlocks.flatMap { it.lines }.filter { line ->
                            val box = line.boundingBox
                            box != null && box.centerY() >= threshold
                        }.sortedBy { it.boundingBox?.top ?: 0 }
                        val text = lines.joinToString(" ") { it.text }.trim().replace(Regex("\\s+"), " ")
                        observe(text)
                    }.addOnFailureListener { e -> status.text = "OCR: ${e.localizedMessage}" }
                        .addOnCompleteListener { busy.set(false); frame.close() }
                }
                p.unbindAll()
                p.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, cameraPreview, analyzer)
                started = true; startButton.text = "STOP"; window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                status.text = "Snímám titulky · připojte Bluetooth reproduktor v nastavení telefonu"
            } catch (e: Exception) { status.text = "Kamera: ${e.localizedMessage}" }
        }, ContextCompat.getMainExecutor(this))
    }
    private fun observe(text: String) {
        if (!started || text.length < 3) return
        val now = SystemClock.elapsedRealtime()
        if (text != lastCandidate) {
            lastCandidate = text; candidateSince = now
        }
        lastSeen = now
        if (now - candidateSince < 900 || recent.contains(text) || text == lastSpoken) return
        if (!text.last().let { it == '.' || it == '!' || it == '?' } && now - candidateSince < 1900) return
        recent.addLast(text); while (recent.size > 20) recent.removeFirst()
        lastSpoken = text
        english.text = "EN: $text"
        lifecycleScope.launch {
            try {
                val translated = withContext(Dispatchers.IO) { translator.translate(text) }
                czech.text = "CZ: $translated"
                delay(delaySeconds * 1000L)
                if (started) speak(translated)
            } catch (e: Exception) { status.text = "Překlad: ${e.localizedMessage}" }
        }
    }
    private val speechQueue = ArrayDeque<String>()
    private var speaking = false
    private fun speak(text: String) {
        speechQueue.addLast(text)
        playNext()
    }
    private fun playNext() {
        if (speaking || speechQueue.isEmpty()) return
        val engine = tts ?: return
        speaking = true
        maleVoice?.let { engine.voice = it }
        val item = speechQueue.removeFirst()
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = runOnUiThread { speaking = false; playNext() }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = runOnUiThread { speaking = false; playNext() }
        })
        if (engine.speak(item, TextToSpeech.QUEUE_FLUSH, null, "camera-${SystemClock.elapsedRealtime()}") == TextToSpeech.ERROR) {
            speaking = false; playNext()
        }
    }
    private fun stopCamera() {
        started = false; provider?.unbindAll(); startButton.text = "START"
        speechQueue.clear(); speaking = false; tts?.stop()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status.text = "Zastaveno"
    }
    override fun onDestroy() {
        stopCamera(); recognizer.close(); translator.close(); tts?.shutdown(); worker.shutdown()
        super.onDestroy()
    }
}
