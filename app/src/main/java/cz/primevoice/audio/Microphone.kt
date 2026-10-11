package cz.primevoice.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

class AudioInputException(val code: String) : IOException(code)

/** Actual PCM16 mono 16kHz input. onBlock reuses its buffer: copy before enqueue. */
class Microphone(context: Context) : Closeable {
    private val app = context.applicationContext
    private val captureMutex = Mutex()
    private val closed = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var lastRoute: AudioDeviceInfo? = null
    val routedDevice: AudioDeviceInfo? get() = lastRoute

    @SuppressLint("MissingPermission")
    suspend fun capture(onBlock: suspend (ShortArray, Int) -> Unit) {
        check(captureMutex.tryLock()) { "MIC_ALREADY_RUNNING" }
        try {
            check(!closed.get()) { "MIC_CLOSED" }
            currentCoroutineContext().ensureActive()
            stopRequested.set(false)
            withContext(Dispatchers.IO) {
                if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    throw SecurityException("MIC_PERMISSION_DENIED")
                }
                val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (minimum <= 0) throw AudioInputException("MIC_FORMAT_UNSUPPORTED")
                val input = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                    .setBufferSizeInBytes(maxOf(minimum, FRAME_SAMPLES * 2 * 4)).build()
                recorder = input
                try {
                    currentCoroutineContext().ensureActive()
                    if (stopRequested.get() || closed.get()) return@withContext
                    if (input.state != AudioRecord.STATE_INITIALIZED) throw AudioInputException("MIC_INITIALIZATION_FAILED")
                    val audio = app.getSystemService(AudioManager::class.java)
                    val phoneMic = audio.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
                    if (phoneMic != null) input.setPreferredDevice(phoneMic)
                    input.startRecording()
                    if (input.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw AudioInputException("MIC_START_FAILED")
                    val samples = ShortArray(FRAME_SAMPLES)
                    while (!stopRequested.get() && !closed.get()) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                        currentCoroutineContext().ensureActive()
                        if (stopRequested.get() || closed.get()) break
                        if (count < 0) throw AudioInputException(if (count == AudioRecord.ERROR_DEAD_OBJECT) "MIC_DEVICE_LOST" else "MIC_READ_FAILED")
                        if (count == 0) throw AudioInputException("MIC_EMPTY_READ")
                        lastRoute = input.routedDevice
                        if (Build.VERSION.SDK_INT >= 29 && input.activeRecordingConfiguration?.isClientSilenced == true) {
                            throw AudioInputException("MIC_SILENCED_BY_SYSTEM")
                        }
                        onBlock(samples, count)
                    }
                } finally {
                    recorder = null
                    try {
                        if (input.recordingState == AudioRecord.RECORDSTATE_RECORDING) input.stop()
                    } catch (_: IllegalStateException) {
                    } finally {
                        input.release()
                        lastRoute = null
                    }
                }
            }
        } finally {
            captureMutex.unlock()
        }
    }
    fun stop() {
        stopRequested.set(true)
        try { recorder?.stop() } catch (_: IllegalStateException) { }
    }
    override fun close() { closed.set(true); stop() }
    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_SAMPLES = 320
    }
}
