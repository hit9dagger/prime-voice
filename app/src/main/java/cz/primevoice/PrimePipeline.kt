package cz.primevoice

import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import cz.primevoice.audio.Microphone
import cz.primevoice.audio.SpeechOutput
import cz.primevoice.audio.WavInput
import cz.primevoice.engine.Models
import cz.primevoice.engine.Recognition
import cz.primevoice.engine.RecognitionResult
import cz.primevoice.engine.Translation
import cz.primevoice.sync.SpeakerRegistry
import cz.primevoice.sync.SyncClock
import cz.primevoice.sync.SyncSlot
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.sqrt

private data class Block(val samples: ShortArray, val start: Long, val end: Long, val generation: Long)
private data class Utterance(val result: RecognitionResult, val start: Long, val end: Long)
class PrimePipeline(private val context: Context) {
    private val microphone = Microphone(context)
    private val output = SpeechOutput(context)
    private val translation = Translation()
    private var recognition: Recognition? = null
    private val generation = AtomicLong()
    private val storageBlocked = AtomicBoolean(false)
    private val outputAllowed = AtomicBoolean(true)
    fun interrupt() {
        outputAllowed.set(false)
        generation.incrementAndGet()
        output.stopAll()
        PrimeState.error("AUDIO_INTERRUPTED", "Hovor nebo změna zvukového výstupu. Fronta zůstává uložena.")
    }
    fun resume() { outputAllowed.set(true); generation.incrementAndGet() }
    fun stop() { microphone.stop(); output.stopAll() }

    suspend fun run(file: Uri? = null): Unit = coroutineScope {
        val pending = PendingStore(File(context.noBackupFilesDir, "pending"))
        val saved = withContext(Dispatchers.IO) { pending.readAll() }
        require(saved.size <= CAPACITY) { "QUEUE_CAPACITY" }
        // Saved elapsedRealtime values may belong to an earlier boot. Keep original journal/UI
        // metadata; map recovery playback to a new ordered clock instead of clamping every item.
        val replayTimes = saved.mapIndexed { index, line -> line.id to (index * 2L to index * 2L + 1L) }.toMap()
        val permits = Semaphore(CAPACITY)
        repeat(saved.size) { permits.acquire() }
        val input = Channel<Block>(32)
        val recognized = Channel<Utterance>(4)
        val translated = Channel<Line>(CAPACITY)
        val lanes = mutableMapOf<String, Channel<Line>>()
        val playbackPermits = Semaphore(4)
        val speakerRegistry = SpeakerRegistry()
        val sync = SyncClock()
        val session = UUID.randomUUID().toString().take(8)
        var nextVoice = 0
        fun backlog() = PrimeState.ui.update { it.copy(backlog = CAPACITY - permits.availablePermits) }
        fun canAdmit(): Boolean = outputAllowed.get() && !storageBlocked.get() && permits.availablePermits > 0 && pending.hasSpace()
        suspend fun waitForOutput() {
            while (!outputAllowed.get() || !hasHeadphones()) delay(250)
        }
        try {
            PrimeState.ui.update { it.copy(active = true, status = "Načítám lokální modely", error = "") }
            val models = Models(context)
            check(models.asrReady()) { "ASR_MODEL_MISSING" }
            check(translation.ready()) { "TRANSLATION_MODEL_MISSING" }
            withContext(Dispatchers.IO) {
                recognition = Recognition(models, PrimeState.ui.value.characterVoice && models.speakerReady())
            }
            output.initialize()
            val voices = output.voices()
            PrimeState.ui.update { it.copy(
                voices = voices.map { voice -> voice.name to voice.locale.displayName },
                engine = output.engineLabel,
                status = "Poslouchám"
            ) }
            if (voices.size < 2 && PrimeState.ui.value.characterVoice) {
                PrimeState.error("ONE_CZECH_VOICE", "Engine nabízí jen jeden český hlas; postavy nebudou mít odlišné hlasy.")
            }
            saved.forEach { PrimeState.line(it); translated.send(it) }
            backlog()

            // One bounded synthesis queue; each speaker has an independent playback lane.
            val synthesisJob = launch(Dispatchers.Default) {
                for (queuedLine in translated) {
                    val requestedVoice = PrimeState.ui.value.speakerVoices[queuedLine.speaker] ?: queuedLine.voice
                    val actualVoice = voices.firstOrNull { it.name == requestedVoice }?.name ?: voices.first().name
                    val line = if (actualVoice == queuedLine.voice) queuedLine else queuedLine.copy(voice = actualVoice)
                    if (line != queuedLine) {
                        withContext(NonCancellable + Dispatchers.IO) { pending.save(line) }
                        PrimeState.line(line)
                        if (voices.none { it.name == requestedVoice })
                            PrimeState.error("VOICE_FALLBACK", "Původní hlas není dostupný; uložená věta použije dostupný lokální český hlas.")
                    }
                    val wav = pending.audio(line)
                    while (isActive) {
                        try {
                            val started = SystemClock.elapsedRealtime()
                            // Always regenerate after restart: a terminated synthesis may have left a partial WAV.
                            output.synthesize(line.czech, line.voice.ifBlank { null }, wav)
                            PrimeState.ui.update { it.copy(synthesisMs = SystemClock.elapsedRealtime() - started) }
                            break
                        } catch (e: CancellationException) {
                            currentCoroutineContext().ensureActive()
                            delay(1000)
                        }
                        catch (_: Exception) {
                            PrimeState.error("TTS_SYNTHESIS", "Syntéza selhala. Dokončený překlad zůstává ve frontě; zkouším znovu.")
                            delay(3000)
                        }
                    }
                    val lane = lanes.getOrPut(line.speaker) {
                        Channel<Line>(CAPACITY).also { channel ->
                            launch(Dispatchers.IO) {
                                for (item in channel) {
                                    var planned: SyncSlot? = null
                                    var played = false
                                    while (isActive) {
                                        waitForOutput()
                                        try {
                                            if (!played) playbackPermits.withPermit {
                                                val now = SystemClock.elapsedRealtime()
                                                val recovery = replayTimes[item.id]
                                                val start = recovery?.first ?: item.sourceStart
                                                val end = recovery?.second ?: item.sourceEnd
                                                val slot = planned ?: sync.schedule(item.speaker, start, end, now, output.durationMs(pending.audio(item))).also { planned = it }
                                                delay((slot.startMs - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                                                waitForOutput()
                                                output.play(pending.audio(item),
                                                    max(slot.speed, PrimeState.ui.value.speed).coerceIn(1f, 1.35f),
                                                    PrimeState.ui.value.volume) {
                                                    PrimeState.lineStatus(item.id, "Přehrávám")
                                                    PrimeState.ui.update { it.copy(
                                                        latencyMs = if (replayTimes.containsKey(item.id)) 0L else (SystemClock.elapsedRealtime() - item.sourceEnd).coerceAtLeast(0)
                                                    ) }
                                                }
                                                sync.markFinished(item.speaker, SystemClock.elapsedRealtime())
                                                played = true
                                            }
                                            withContext(NonCancellable + Dispatchers.IO) { pending.complete(item) }
                                            PrimeState.lineStatus(item.id, "Přehráno")
                                            permits.release(); backlog()
                                            break
                                        } catch (e: CancellationException) {
                                            currentCoroutineContext().ensureActive()
                                            PrimeState.lineStatus(item.id, "Čeká na obnovení výstupu")
                                            delay(1000)
                                        }
                                        catch (_: Exception) {
                                            PrimeState.lineStatus(item.id, "Čeká na obnovení výstupu")
                                            if (played) PrimeState.error("QUEUE_ACK", "Věta dohrála, ale zápis dokončení selhal. Opakuji zápis bez opakování hlasu.")
                                            else PrimeState.error("AUDIO_PLAYBACK", "Přehrávání bylo přerušeno. Věta zůstává ve frontě a bude zopakována.")
                                            delay(2000)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    lane.send(line)
                }
                lanes.values.forEach { it.close() }
            }

            launch(Dispatchers.Default) {
                for (utterance in recognized) {
                    // Permit is held until playback succeeds. Completed translations are never evicted.
                    var retained = false
                    try {
                        val started = SystemClock.elapsedRealtime()
                        val cs = translation.translate(utterance.result.text)
                        check(cs.isNotBlank())
                        val identity = if (PrimeState.ui.value.characterVoice)
                            speakerRegistry.identify(utterance.result.embedding, utterance.result.speakerFrames) else "unknown"
                        val speaker = if (identity == "unknown") "unknown" else "$session:$identity"
                        val selected = PrimeState.ui.value.speakerVoices[speaker]
                            ?: voices.getOrNull(if (speaker == "unknown") 0 else nextVoice++ % voices.size)?.name.orEmpty()
                        PrimeState.ui.update { it.copy(
                            speakerVoices = it.speakerVoices + (speaker to selected),
                            translationMs = SystemClock.elapsedRealtime() - started
                        ) }
                        val line = Line(UUID.randomUUID().toString(), speaker, utterance.start,
                            utterance.end, utterance.result.text, cs, selected)
                        // If disk is full retain in this bounded worker and stop accepting further input.
                        while (true) {
                            try {
                                withContext(NonCancellable + Dispatchers.IO) { pending.save(line) }
                                storageBlocked.set(false)
                                break
                            }
                            catch (e: CancellationException) { throw e }
                            catch (_: Exception) {
                                storageBlocked.set(true)
                                PrimeState.error("QUEUE_WRITE", "Nelze uložit hotový překlad. Uvolněte místo; příjem je pozastaven.")
                                delay(2000)
                            }
                        }
                        retained = true
                        PrimeState.line(line)
                        translated.send(line)
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { PrimeState.error("TRANSLATION_FAILED", "Překlad této anglické promluvy selhal.") }
                    finally { if (!retained) permits.release(); backlog() }
                }
                translated.close()
            }

            launch(Dispatchers.Default) {
                var streamOrigin = 0L
                var previousGeneration = -1L
                var segmentMs = 0L
                var asrNanos = 0L
                var lastEnd = 0L
                var lastBlockEnd = 0L
                suspend fun emit(result: RecognitionResult?, end: Long) {
                    if (result == null || result.text.isBlank()) return
                    val start = result.sourceStartSeconds?.let { streamOrigin + (it * 1000).toLong() }
                        ?: max(lastEnd, end - segmentMs)
                    val speechEnd = result.sourceEndSeconds?.let { streamOrigin + (it * 1000).toLong() } ?: end
                    // Admission pauses BEFORE a translation is completed; this never evicts a finished result.
                    permits.acquire()
                    backlog()
                    recognized.send(Utterance(result, start.coerceAtMost(end), speechEnd.coerceIn(start.coerceAtMost(end), end)))
                    lastEnd = speechEnd
                    PrimeState.ui.update { it.copy(asrMs = asrNanos / 1_000_000L) }
                    asrNanos = 0
                    segmentMs = 0
                }
                for (block in input) {
                    lastBlockEnd = block.end
                    val recognizer = checkNotNull(recognition)
                    if (block.generation != previousGeneration) {
                        recognizer.reset()
                        streamOrigin = block.start
                        lastEnd = block.start
                        segmentMs = 0
                        asrNanos = 0
                        previousGeneration = block.generation
                    }
                    val started = SystemClock.elapsedRealtimeNanos()
                    val result = recognizer.accept(block.samples, block.samples.size)
                    asrNanos += SystemClock.elapsedRealtimeNanos() - started
                    segmentMs += block.end - block.start
                    emit(result, block.end)
                    if (segmentMs >= 10_000) {
                        emit(recognizer.finish(), block.end)
                        recognizer.reset()
                        streamOrigin = block.end
                        segmentMs = 0
                    }
                }
                emit(recognition?.finish(), lastBlockEnd)
                recognized.close()
            }

            launch(Dispatchers.IO) {
                var fileSamples = 0L
                var lastInputType = -1
                val fileOrigin = SystemClock.elapsedRealtime()
                suspend fun receive(samples: ShortArray, count: Int) {
                    if (file == null) {
                        val inputType = microphone.routedDevice?.type ?: -1
                        if (inputType != lastInputType) {
                            lastInputType = inputType
                            PrimeState.ui.update { it.copy(inputRouteType = inputType) }
                            if (inputType != -1 && inputType != android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC)
                                PrimeState.error("MIC_ROUTE", "Android používá jiný vstup než mikrofon telefonu. Zkontrolujte připojená zařízení.")
                        }
                    }
                    val duration = count * 1000L / 16000
                    fileSamples += count
                    if (file != null) {
                        // Replay at the original sample clock so ASR and live mode use the same time base.
                        delay((fileOrigin + fileSamples * 1000L / 16000 - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                    }
                    val end = if (file == null) SystemClock.elapsedRealtime() else fileOrigin + fileSamples * 1000L / 16000
                    var sum = 0.0
                    for (i in 0 until count) sum += samples[i].toDouble() * samples[i]
                    val level = if (count == 0) 0f else (sqrt(sum / count) / 32768).toFloat()
                    val allowed = canAdmit()
                    PrimeState.ui.update { it.copy(level = level, status =
                        if (allowed) "Poslouchám" else "Příjem pozastaven — dopřehrávám překlady") }
                    if (file != null) {
                        while (!canAdmit()) delay(100)
                        input.send(Block(samples.copyOf(count), end - duration, end, generation.get()))
                    } else if (!allowed || !input.trySend(Block(samples.copyOf(count), end - duration, end, generation.get())).isSuccess) {
                        generation.incrementAndGet()
                        PrimeState.ui.update { it.copy(missedBlocks = it.missedBlocks + 1) }
                    }
                }
                try {
                    if (file == null) microphone.capture(::receive)
                    else context.contentResolver.openInputStream(file)?.use { WavInput.read(it, ::receive) }
                        ?: error("WAV_OPEN")
                } finally { input.close() }
            }
            if (file == null) awaitCancellation() else synthesisJob.join()
        } finally {
            microphone.stop()
            output.stopAll()
            // coroutineScope waits for its children before returning; close happens in release().
        }
    }
    suspend fun release() = withContext(NonCancellable + Dispatchers.IO) {
        microphone.close()
        recognition?.close()
        recognition = null
        translation.close()
        output.close()
        PrimeState.ui.update { it.copy(active = false, status = "Zastaveno", level = 0f) }
    }
    private fun hasHeadphones(): Boolean = context.getSystemService(AudioManager::class.java)
        .getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in setOf(
            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
            android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
            android.media.AudioDeviceInfo.TYPE_BLE_HEADSET
        ) }
    companion object { const val CAPACITY = 24 }
}
