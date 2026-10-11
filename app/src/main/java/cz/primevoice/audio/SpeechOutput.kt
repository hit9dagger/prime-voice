package cz.primevoice.audio

import android.content.Context
import android.media.*
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SpeechOutputException(val code: String, message: String, cause: Throwable? = null) : IOException(code + ": " + message, cause)

/** Offline Czech platform TTS -> private WAV -> independent AudioTracks. No voice cloning. */
class SpeechOutput(context: Context) : Closeable {
    private val app = context.applicationContext
    private val manager = requireNotNull(app.getSystemService(AudioManager::class.java))
    private val closed = AtomicBoolean(false)
    private val initMutex = Mutex()
    private val synthesisMutex = Mutex()
    private val operations = mutableSetOf<Job>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val sherpaPackage = "com.k2fsa.sherpa.onnx.tts.engine"
    private val preferredEngine: String? = runCatching {
        @Suppress("DEPRECATION")
        app.packageManager.getPackageInfo(sherpaPackage, 0)
        sherpaPackage
    }.getOrNull()
    @Volatile private var engine: TextToSpeech? = null
    @Volatile private var availableVoices: List<Voice> = emptyList()
    @Volatile var preferredOutputId: Int? = null
    val engineLabel: String get() = if (preferredEngine != null) "Sherpa-ONNX (samostatný TTS engine)" else engine?.defaultEngine ?: "Výchozí systémový TTS"
    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit
        override fun onDone(utteranceId: String?) { utteranceId?.let { pending.remove(it)?.complete(Unit) } }
        @Deprecated("Platform callback")
        override fun onError(utteranceId: String?) { onError(utteranceId, TextToSpeech.ERROR) }
        override fun onError(utteranceId: String?, errorCode: Int) {
            utteranceId?.let { pending.remove(it)?.completeExceptionally(SpeechOutputException("TTS_SYNTHESIS", "Syntéza selhala, kód " + errorCode)) }
        }
        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            utteranceId?.let { pending.remove(it)?.cancel(CancellationException("Syntéza byla zastavena")) }
        }
    }
    suspend fun initialize() = operation { ensureInitialized() }
    fun voices(): List<Voice> = availableVoices.toList()
    fun outputs(): List<AudioDeviceInfo> = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter(::isHeadphone)
    fun durationMs(file: File): Long = PcmWave.read(file).durationMs

    private suspend fun ensureInitialized() = initMutex.withLock {
        checkOpen()
        if (engine != null && availableVoices.isNotEmpty()) return@withLock
        val ready = CompletableDeferred<Int>()
        val created = withContext(Dispatchers.Main.immediate) {
            val callback = TextToSpeech.OnInitListener { ready.complete(it) }
            if (preferredEngine == null) TextToSpeech(app, callback) else TextToSpeech(app, callback, preferredEngine)
        }
        engine = created
        try {
            checkOpen()
            val status = withTimeoutOrNull(20_000) { ready.await() }
                ?: throw SpeechOutputException("TTS_INIT_TIMEOUT", "Inicializace hlasu překročila časový limit")
            if (status != TextToSpeech.SUCCESS) throw SpeechOutputException("TTS_INIT", "Český hlasový engine nelze spustit")
            created.setOnUtteranceProgressListener(listener)
            val local = created.voices.orEmpty().filter {
                it.locale.language in setOf("cs", "ces") && !it.isNetworkConnectionRequired &&
                    !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
            }.sortedWith(compareByDescending<Voice> { it.quality }.thenBy { it.latency }.thenBy { it.name })
            if (local.isEmpty()) throw SpeechOutputException("TTS_CZECH_MISSING",
                "Chybí stažený český hlas. Nainstalujte český Piper TTS engine nebo offline český hlas v nastavení TTS.")
            availableVoices = local
        } catch (failure: Throwable) {
            if (engine === created) engine = null
            availableVoices = emptyList()
            created.shutdown()
            throw failure
        }
    }

    /** Serial synthesis, independent playback. External engine may finish native work after cancellation. */
    suspend fun synthesize(text: String, voiceName: String?, destination: File): Unit = operation {
        require(text.isNotBlank())
        require(text.length <= 1500) { "TTS_TEXT_LIMIT" }
        ensureInitialized()
        synthesisMutex.withLock {
            currentCoroutineContext().ensureActive()
            val tts = engine ?: throw SpeechOutputException("TTS_INIT", "Hlasový engine není připraven")
            val voice = if (voiceName == null) availableVoices.first() else availableVoices.firstOrNull { it.name == voiceName }
                ?: throw SpeechOutputException("TTS_VOICE_MISSING", "Přiřazený offline český hlas již není dostupný")
            if (tts.setVoice(voice) != TextToSpeech.SUCCESS || tts.setSpeechRate(1f) != TextToSpeech.SUCCESS || tts.setPitch(1f) != TextToSpeech.SUCCESS) {
                throw SpeechOutputException("TTS_VOICE", "Hlas nelze nastavit")
            }
            destination.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) throw IOException("Nelze vytvořit adresář syntézy") }
            val id = UUID.randomUUID().toString()
            val done = CompletableDeferred<Unit>()
            pending[id] = done
            var success = false
            try {
                val params = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id) }
                if (tts.synthesizeToFile(text, params, destination, id) != TextToSpeech.SUCCESS) throw SpeechOutputException("TTS_SUBMIT", "Engine odmítl syntézu")
                withTimeoutOrNull(120_000) { done.await(); true } ?: throw SpeechOutputException("TTS_TIMEOUT", "Syntéza překročila časový limit")
                currentCoroutineContext().ensureActive()
                PcmWave.read(destination)
                success = true
            } finally {
                pending.remove(id)
                if (!success) { tts.stop(); destination.delete() }
            }
        }
    }

    /** Independent track. Returns after all source frames drain; onStarted is software, not acoustic BT time. */
    suspend fun play(file: File, speed: Float, volume: Float, onStarted: () -> Unit): Unit = operation {
        require(speed.isFinite() && speed in 1f..1.35f)
        require(volume.isFinite() && volume in 0f..1f)
        val wave = PcmWave.read(file)
        val devices = outputs()
        val selected = preferredOutputId?.let { id -> devices.firstOrNull { it.id == id } }
            ?: if (preferredOutputId == null) devices.sortedBy {
                if (it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    (Build.VERSION.SDK_INT >= 31 && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)) 0 else 1
            }.firstOrNull() else null
        if (selected == null) throw SpeechOutputException("OUTPUT_HEADPHONES_REQUIRED", "Připojte sluchátka; reproduktor je blokován proti zpětné vazbě")
        val mask = if (wave.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minimum = AudioTrack.getMinBufferSize(wave.sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) throw SpeechOutputException("OUTPUT_FORMAT", "AudioTrack nepodporuje formát hlasu")
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(wave.sampleRate).setChannelMask(mask).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minimum * 2, wave.sampleRate * wave.frameBytes / 5)).build()
        val routeFailure = CompletableDeferred<Unit>()
        val routingListener = AudioRouting.OnRoutingChangedListener { routing ->
            val actual = routing.routedDevice
            if (actual != null && (!isHeadphone(actual) || actual.id != selected.id)) {
                routeFailure.completeExceptionally(SpeechOutputException("OUTPUT_ROUTE_CHANGED", "Výstup sluchátek se změnil"))
                runCatching { track.pause() }
            }
        }
        var listenerAdded = false
        try {
            if (track.state != AudioTrack.STATE_INITIALIZED) throw SpeechOutputException("OUTPUT_INIT", "AudioTrack nebyl inicializován")
            if (!track.setPreferredDevice(selected)) throw SpeechOutputException("OUTPUT_ROUTE_UNAVAILABLE", "Android odmítl směrování do sluchátek")
            track.setVolume(volume)
            track.playbackParams = PlaybackParams().allowDefaults().setSpeed(speed).setPitch(1f)
                .setAudioFallbackMode(PlaybackParams.AUDIO_FALLBACK_MODE_FAIL)
            track.addOnRoutingChangedListener(routingListener, Handler(Looper.getMainLooper()))
            listenerAdded = true
            track.play()
            // Some devices publish routing only after initial PCM. Prime with SILENCE;
            // never submit translated speech until the real route is verified.
            val silence = ByteArray(maxOf(minimum * 2, wave.frameBytes * wave.sampleRate / 5))
            var primed = 0
            withTimeoutOrNull(2_000) {
                while (track.routedDevice == null) {
                    checkRouteFailure(routeFailure)
                    if (primed < silence.size) {
                        val n = track.write(silence, primed, silence.size - primed, AudioTrack.WRITE_NON_BLOCKING)
                        if (n < 0) throw SpeechOutputException("OUTPUT_WRITE", "Nelze připravit zvukový výstup")
                        primed += n
                    }
                    delay(10)
                }
                true
            } ?: throw SpeechOutputException("OUTPUT_ROUTE_TIMEOUT", "Android nepotvrdil směrování sluchátek")
            validateRoute(track, selected)
            var started = false
            RandomAccessFile(file, "r").use { input ->
                input.seek(wave.dataOffset)
                val buffer = ByteArray(8192 - 8192 % wave.frameBytes)
                var remaining = wave.dataBytes
                while (remaining > 0L) {
                    currentCoroutineContext().ensureActive()
                    checkRouteFailure(routeFailure)
                    validateRoute(track, selected)
                    val count = minOf(buffer.size.toLong(), remaining).toInt()
                    input.readFully(buffer, 0, count)
                    var offset = 0
                    while (offset < count) {
                        currentCoroutineContext().ensureActive()
                        checkRouteFailure(routeFailure)
                        validateRoute(track, selected)
                        val written = track.write(buffer, offset, count - offset, AudioTrack.WRITE_NON_BLOCKING)
                        if (written < 0) throw SpeechOutputException("OUTPUT_WRITE", "AudioTrack zápis selhal, kód " + written)
                        if (written == 0) delay(5) else {
                            offset += written
                            if (!started) { started = true; onStarted() }
                        }
                    }
                    remaining -= count
                }
            }
            val totalFrames = (wave.dataBytes + primed) / wave.frameBytes
            withTimeoutOrNull(wave.durationMs + 10_000) {
                while ((track.playbackHeadPosition.toLong() and 0xffffffffL) < totalFrames) {
                    currentCoroutineContext().ensureActive()
                    checkRouteFailure(routeFailure)
                    validateRoute(track, selected)
                    delay(10)
                }
                true
            } ?: throw SpeechOutputException("OUTPUT_DRAIN_TIMEOUT", "Přehrávání se nedokončilo v časovém limitu")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: SpeechOutputException) { throw failure }
        catch (failure: Exception) { throw SpeechOutputException("OUTPUT_FAILURE", "Přehrávání selhalo", failure) }
        finally {
            if (listenerAdded) runCatching { track.removeOnRoutingChangedListener(routingListener) }
            runCatching { track.pause() }; runCatching { track.flush() }; track.release()
        }
    }
    private suspend fun checkRouteFailure(failure: CompletableDeferred<Unit>) { if (failure.isCompleted) failure.await() }
    private fun validateRoute(track: AudioTrack, selected: AudioDeviceInfo) {
        val actual = track.routedDevice
        if (actual == null || !isHeadphone(actual) || actual.id != selected.id) throw SpeechOutputException("OUTPUT_ROUTE_LOST", "Sluchátka byla odpojena nebo Android změnil zvukový výstup")
    }
    fun stopAll() {
        val jobs = synchronized(operations) { operations.toList() }
        jobs.forEach { it.cancel(CancellationException("Hlasový výstup byl zastaven")) }
        pending.values.forEach { it.cancel() }; pending.clear(); engine?.stop()
    }
    override fun close() {
        if (closed.compareAndSet(false, true)) { stopAll(); engine?.shutdown(); engine = null; availableVoices = emptyList() }
    }
    private fun checkOpen() { check(!closed.get()) { "Hlasový výstup je uzavřen" } }
    private suspend fun <T> operation(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext().job
        synchronized(operations) { checkOpen(); operations.add(job) }
        try { block() } finally { synchronized(operations) { operations.remove(job) } }
    }
    companion object {
        private fun isHeadphone(device: AudioDeviceInfo): Boolean = when (device.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET -> true
            else -> (Build.VERSION.SDK_INT >= 28 && device.type == AudioDeviceInfo.TYPE_HEARING_AID) ||
                (Build.VERSION.SDK_INT >= 31 && device.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
    }
}

/** Strict bounded PCM WAV parsing; does not load the waveform into memory. */
internal data class PcmWave(val sampleRate: Int, val channels: Int, val dataOffset: Long, val dataBytes: Long) {
    val frameBytes: Int get() = channels * 2
    val durationMs: Long get() = (dataBytes * 1000 + sampleRate * frameBytes - 1) / (sampleRate * frameBytes)
    companion object {
        fun read(file: File): PcmWave = RandomAccessFile(file, "r").use { input ->
            fun fail(detail: String): Nothing = throw SpeechOutputException("TTS_WAV_INVALID", detail)
            fun tag(): String { val bytes = ByteArray(4); input.readFully(bytes); return String(bytes, Charsets.US_ASCII) }
            fun u16(): Int = input.readUnsignedByte() or (input.readUnsignedByte() shl 8)
            fun u32(): Long = u16().toLong() or (u16().toLong() shl 16)
            if (input.length() < 44 || input.length() > 128L * 1024 * 1024) fail("Neplatná velikost WAV")
            if (tag() != "RIFF") fail("Očekáván RIFF WAV")
            val end = u32() + 8
            if (end > input.length() || end < 44) fail("Neúplný RIFF")
            if (tag() != "WAVE") fail("Očekáván WAVE")
            var rate = 0; var channels = 0; var dataOffset = -1L; var dataBytes = 0L
            while (input.filePointer < end) {
                if (end - input.filePointer < 8) fail("Neúplná hlavička chunku")
                val kind = tag(); val size = u32(); val start = input.filePointer
                val next = start + size + size % 2
                if (next > end) fail("Neúplná data nebo výplň WAV")
                when (kind) {
                    "fmt " -> {
                        if (rate != 0 || size < 16) fail("Neplatný nebo duplicitní formát WAV")
                        val format = u16(); channels = u16(); val sampleRate = u32()
                        val byteRate = u32(); val alignment = u16(); val bits = u16()
                        if (format != 1 || bits != 16 || channels !in 1..2 || sampleRate !in 8000L..96000L) fail("Podporován pouze PCM16 mono/stereo, 8–96 kHz")
                        rate = sampleRate.toInt()
                        if (alignment != channels * 2 || byteRate != sampleRate * channels * 2) fail("Nekonzistentní formát WAV")
                    }
                    "data" -> {
                        if (dataOffset != -1L) fail("Více datových chunků není podporováno")
                        dataOffset = start; dataBytes = size
                    }
                }
                input.seek(next)
            }
            if (rate == 0 || dataOffset < 0 || dataBytes == 0L || dataBytes % (channels * 2) != 0L) fail("Chybí formát nebo úplné PCM rámce")
            PcmWave(rate, channels, dataOffset, dataBytes)
        }
    }
}
