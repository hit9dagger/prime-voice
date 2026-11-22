package cz.primevoice.speech

import android.content.*
import android.media.*
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class OutputState(val description: String = "Systémový mediální výstup", val needsResume: Boolean = false)

/** Uses the system MEDIA route; never forces Bluetooth SCO (which would change TV-mic quality). */
class AudioOutputManager(private val context: Context, private val onAllowed: (Boolean) -> Unit) {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val mutable = MutableStateFlow(OutputState())
    val state = mutable.asStateFlow()
    val attributes: AudioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private var hasFocus = false
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes).setAcceptsDelayedFocusGain(false)
        .setOnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_GAIN) {
                hasFocus = true
                if (!mutable.value.needsResume) onAllowed(true)
            } else if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                hasFocus = false
                mutable.value = mutable.value.copy(needsResume = true)
                onAllowed(false)
            }
        }.build()
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) routeLost()
        }
    }
    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            if (removedDevices.any { it.isSink && it.type in externalTypes }) routeLost()
            refresh()
        }
    }
    init {
        ContextCompat.registerReceiver(context, receiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
        manager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        refresh()
    }
    private val externalTypes get() = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET)
    private fun routeLost() {
        mutable.value = mutable.value.copy(needsResume = true)
        onAllowed(false) // Currently playing utterance is allowed to finish. No new one starts.
    }
    private fun refresh() {
        val connected = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val bluetooth = connected.any { it.type in setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER) }
        val wired = connected.any { it.type in externalTypes && it.type != AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
        val label = if (bluetooth) "Bluetooth připojen • výstup řídí Android"
            else if (wired) "Externí výstup připojen • řídí Android" else "Reproduktor telefonu • výstup řídí Android"
        mutable.value = mutable.value.copy(description = label)
    }
    fun acquire(): Boolean {
        if (mutable.value.needsResume) return false
        if (hasFocus) return true
        hasFocus = manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasFocus
    }
    fun resume() { mutable.value = mutable.value.copy(needsResume = false); onAllowed(true) }
    fun release() { if (hasFocus) manager.abandonAudioFocusRequest(focus); hasFocus = false }
    fun close() {
        release(); manager.unregisterAudioDeviceCallback(callback); context.unregisterReceiver(receiver)
    }
}
