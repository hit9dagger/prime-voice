package cz.primevoice.speech

import android.content.Context
import android.content.Intent
import android.os.*
import android.speech.*
import cz.primevoice.core.SpeechEvidence
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AsrState(val available: Boolean, val listening: Boolean = false, val message: String = "Vypnuto")

/** Short on-device sessions triggered by visible subtitles. Never falls back to a cloud recognizer. */
class SpeechRecognitionProcessor(private val context: Context, private val onEvidence: (SpeechEvidence) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val available = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    private val mutable = MutableStateFlow(AsrState(available))
    val state = mutable.asStateFlow()
    private var recognizer: SpeechRecognizer? = null
    private var enabled = false
    private var echoSuppressed = false
    private var resourceSuppressed = false
    private var lastVisible = -100_000L
    private var started = 0L
    private var failures = 0
    private var generation = 0
    private var lastSpeechEnd = 0L
    private val intent get() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }
    fun enable(value: Boolean) {
        enabled = value
        if (!value) { cancel(); mutable.value = AsrState(available, false, "Vypnuto") }
        else if (!available) mutable.value = AsrState(false, false, "Offline odposlech není dostupný. Kamera funguje samostatně.")
        else { failures = 0; mutable.value = AsrState(true, false, "Připraven • čeká na titulky"); maybeStart() }
    }
    fun subtitlesVisible() { lastVisible = SystemClock.elapsedRealtime(); maybeStart() }
    fun suppressEcho(value: Boolean) {
        if (value == echoSuppressed) return
        echoSuppressed = value
        if (value) {
            cancel()
            if (enabled) mutable.value = AsrState(available, false, "Pauza mikrofonu během českého dabingu")
        } else if (enabled) handler.postDelayed({ maybeStart() }, 650)
    }
    fun suppressResources(value: Boolean) {
        if (resourceSuppressed == value) return
        resourceSuppressed = value
        if (value) { cancel(); if (enabled) mutable.value = AsrState(available, false, "Pauza mikrofonu během náročné inference") }
        else maybeStart()
    }
    private fun maybeStart() {
        if (Build.VERSION.SDK_INT < 31 || !enabled || !available || echoSuppressed || resourceSuppressed || mutable.value.listening || failures >= 4 ||
            SystemClock.elapsedRealtime() - lastVisible > 3500) return
        val token = ++generation
        try {
            val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context); recognizer = r
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { lastSpeechEnd = SystemClock.elapsedRealtime() }
                override fun onEvent(eventType: Int, params: Bundle?) {}
                override fun onPartialResults(partialResults: Bundle?) { /* Partial ASR never repairs OCR. */ }
                override fun onResults(results: Bundle?) {
                    if (token != generation) return
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    val confidence = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)?.firstOrNull() ?: -1f
                    if (!text.isNullOrBlank() && !echoSuppressed && enabled) {
                        // Unknown engine confidence remains unknown; it is not invented as high confidence.
                        onEvidence(SpeechEvidence(text, started,
                            lastSpeechEnd.takeIf { it >= started } ?: SystemClock.elapsedRealtime(), confidence, true))
                    }
                    failures = 0; finish("Připraven • lokální angličtina", 1000)
                }
                override fun onError(error: Int) {
                    if (token != generation) return
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> finish("Řeč nezachycena • přednost má kamera", 1800)
                        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                            failures = 4; finish("Chybí offline angličtina v rozpoznávači. Používá se kamera.", 0)
                        }
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                            enabled = false; finish("Mikrofon nemá oprávnění. Používá se kamera.", 0)
                        }
                        else -> {
                            failures++
                            finish(if (failures >= 4) "Odposlech pozastaven po chybách. Zkuste přepínač vypnout a zapnout."
                                else "Odposlech se obnovuje ($error)…", failures * 2500L)
                        }
                    }
                }
            })
            started = SystemClock.elapsedRealtime(); lastSpeechEnd = 0
            mutable.value = AsrState(true, true, "Poslouchám anglický dialog")
            r.startListening(intent)
            handler.postDelayed({ if (token == generation && mutable.value.listening) r.stopListening() }, 10_000)
            handler.postDelayed({ if (token == generation && mutable.value.listening) finish("Obnovuji odposlech…", 1800) }, 14_000)
        } catch (_: Exception) {
            failures++; finish("Offline odposlech se nepodařilo spustit. Kamera pokračuje.", 2500)
        }
    }
    private fun finish(message: String, after: Long) {
        cancel(); mutable.value = AsrState(available, false, message)
        if (after > 0) handler.postDelayed({ maybeStart() }, after)
    }
    private fun cancel() {
        generation++; handler.removeCallbacksAndMessages(null)
        recognizer?.cancel(); recognizer?.destroy(); recognizer = null
        mutable.value = mutable.value.copy(listening = false)
    }
    fun close() { enabled = false; cancel() }
}
