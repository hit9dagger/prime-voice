package cz.primevoice.speech

import android.content.Context
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import cz.primevoice.core.Speaker
import cz.primevoice.data.Settings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

data class VoiceOption(val engine: String, val engineLabel: String, val name: String,
                       val quality: Int, val locale: String) {
    val id get() = "$engine|$name"
    val label get() = "$name · $engineLabel"
}
data class TtsState(val loading: Boolean = true, val voices: List<VoiceOption> = emptyList(),
                    val selected: String = "", val speaking: Boolean = false,
                    val error: String? = null, val pitchSupported: Boolean = true)

class CzechTTSManager(private val context: Context, private val output: AudioOutputManager,
                      private val settings: () -> Settings,
                      private val onPreparation: (Long) -> Unit) : Speaker {
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(TtsState())
    val state = mutable.asStateFlow()
    private var engine: TextToSpeech? = null
    private var enginePackage = ""
    private var completion: CompletableDeferred<Unit>? = null
    @Volatile private var currentId = ""
    @Volatile private var requestedAt = 0L

    private suspend fun create(packageName: String? = null): TextToSpeech = withContext(Dispatchers.Main.immediate) {
        val initialized = CompletableDeferred<Int>()
        val instance = TextToSpeech(context, { initialized.complete(it) }, packageName)
        try {
            check(withTimeout(8_000) { initialized.await() } == TextToSpeech.SUCCESS) { "Hlasový modul se nespustil" }
            instance
        } catch (e: Exception) { instance.shutdown(); throw e }
    }
    private fun offlineCzech(voice: Voice) = voice.locale.language in setOf("cs", "ces", "cze") &&
        !voice.isNetworkConnectionRequired && !voice.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)

    suspend fun discover() = mutex.withLock {
        mutable.update { it.copy(loading = true, error = null) }
        try {
            val default = create()
            val installed = default.engines.toList()
            default.shutdown()
            val voices = mutableListOf<VoiceOption>()
            for (info in installed.take(12)) {
                var probe: TextToSpeech? = null
                try {
                    probe = create(info.name)
                    probe.voices.orEmpty().filter(::offlineCzech).forEach {
                        voices.add(VoiceOption(info.name, info.label, it.name, it.quality, it.locale.toLanguageTag()))
                    }
                } catch (_: TimeoutCancellationException) { /* A stalled engine is skipped. */ }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* One broken installed engine must not hide others. */ }
                finally { probe?.shutdown() }
            }
            val sorted = voices.distinctBy { it.id }.sortedWith(
                compareByDescending<VoiceOption> { it.name.contains("jirka", ignoreCase = true) || it.engine.contains("jirka", true) }
                    .thenByDescending { it.quality }.thenBy { it.name })
            val saved = settings()
            val chosen = sorted.firstOrNull { it.engine == saved.engine && it.name == saved.voice } ?: sorted.firstOrNull()
            mutable.value = TtsState(loading = false, voices = sorted, selected = chosen?.id ?: "",
                error = if (chosen == null) "Chybí český offline hlas. Nainstalujte hlasová data v nastavení TTS a obnovte seznam." else null)
            if (chosen != null) activate(chosen)
        } catch (_: TimeoutCancellationException) {
            mutable.update { it.copy(loading = false, error = "Hlasový modul neodpovídá. Zkuste obnovit seznam hlasů.") }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { mutable.update { it.copy(loading = false, error = e.message ?: "TTS není dostupné") } }
    }

    suspend fun select(id: String) = mutex.withLock {
        val choice = mutable.value.voices.firstOrNull { it.id == id } ?: return@withLock
        try { activate(choice); mutable.update { it.copy(selected = id, error = null) } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { mutable.update { it.copy(error = e.message) } }
    }

    private suspend fun activate(choice: VoiceOption) {
        if (engine == null || enginePackage != choice.engine) {
            engine?.shutdown(); engine = null
            engine = create(choice.engine); enginePackage = choice.engine
            engine!!.setAudioAttributes(output.attributes)
            engine!!.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    if (utteranceId == currentId) onPreparation(SystemClock.elapsedRealtime() - requestedAt)
                }
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == currentId) completion?.complete(Unit)
                }
                @Deprecated("Android legacy callback")
                override fun onError(utteranceId: String?) = fail(utteranceId)
                override fun onError(utteranceId: String?, errorCode: Int) = fail(utteranceId)
                override fun onStop(utteranceId: String?, interrupted: Boolean) = fail(utteranceId)
                private fun fail(id: String?) {
                    if (id == currentId) completion?.completeExceptionally(IllegalStateException("Hlasový modul nedokončil větu. Zkuste ji znovu."))
                }
            })
        }
        val voice = engine!!.voices.orEmpty().firstOrNull { it.name == choice.name && offlineCzech(it) }
            ?: error("Vybraný offline hlas již není nainstalovaný")
        check(engine!!.setVoice(voice) == TextToSpeech.SUCCESS) { "Hlasový modul odmítl vybraný hlas" }
    }

    override suspend fun speak(text: String) = mutex.withLock {
        withContext(Dispatchers.Main.immediate) {
            val choice = mutable.value.voices.firstOrNull { it.id == mutable.value.selected }
                ?: error("Není vybraný český offline hlas")
            activate(choice)
            check(output.acquire()) { "Zvukový výstup je pozastaven. Potvrďte pokračování zvuku." }
            val tts = engine!!; val s = settings()
            tts.setSpeechRate(s.rate)
            mutable.update { it.copy(pitchSupported = tts.setPitch(s.pitch) == TextToSpeech.SUCCESS,
                speaking = true, error = null) }
            try {
                // A whole dialogue may exceed engine limits. Chunks still share the single queue slot.
                for (chunk in chunks(text, minOf(TextToSpeech.getMaxSpeechInputLength() - 1, 3500))) {
                    currentId = UUID.randomUUID().toString(); completion = CompletableDeferred()
                    requestedAt = SystemClock.elapsedRealtime()
                    check(tts.speak(chunk, TextToSpeech.QUEUE_ADD, null, currentId) == TextToSpeech.SUCCESS) { "TTS odmítlo větu" }
                    try { withTimeout(180_000) { completion!!.await() } }
                    catch (e: TimeoutCancellationException) {
                        // Fault recovery only: stop a stalled engine before allowing any later utterance.
                        tts.stop(); throw IllegalStateException("TTS nepotvrdilo dokončení do 3 minut. Fronta čeká na opakování.", e)
                    }
                }
            } finally {
                currentId = ""; completion = null
                mutable.update { it.copy(speaking = false) }; output.release()
            }
        }
    }
    private fun chunks(text: String, max: Int): List<String> {
        val result = mutableListOf<String>(); var remaining = text
        while (remaining.length > max) {
            val end = remaining.lastIndexOf(' ', max).takeIf { it > 0 } ?: max
            result.add(remaining.take(end)); remaining = remaining.drop(end).trimStart()
        }
        if (remaining.isNotBlank()) result.add(remaining)
        return result
    }
    fun close() { engine?.stop(); engine?.shutdown(); engine = null; completion?.cancel() }
}
