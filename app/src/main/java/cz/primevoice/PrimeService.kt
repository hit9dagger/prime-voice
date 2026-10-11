package cz.primevoice

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.*
import android.os.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.update

class PrimeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var work: Job? = null
    private var pipeline: PrimePipeline? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var audio: AudioManager
    private var focus: AudioFocusRequest? = null
    private var focusGranted = false
    private var routeAvailable = false
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { routeAvailable = false; updateGate() }
    }
    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) { updateRoute() }
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) { updateRoute() }
    }
    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(AudioManager::class.java)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("listening", "Překlad řeči", NotificationManager.IMPORTANCE_LOW)
        )
        audio.registerAudioDeviceCallback(devices, Handler(mainLooper))
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisy,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
    }
    private fun updateRoute() {
        val privateOutputs = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.type in setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET
        ) }
        PrimeState.ui.update { it.copy(route = if (privateOutputs.isEmpty()) "Sluchátka nejsou připojena" else "Sluchátka dostupná; směrování ověřuje přehrávač") }
        routeAvailable = privateOutputs.isNotEmpty()
        updateGate()
    }
    private fun updateGate() {
        if (focusGranted && routeAvailable && audio.mode == AudioManager.MODE_NORMAL) pipeline?.resume()
        else pipeline?.interrupt()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        if (work?.isActive == true) return START_NOT_STICKY
        val stop = PendingIntent.getService(this, 1, Intent(this, PrimeService::class.java).setAction("STOP"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, "listening")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("PRIME VOICE")
            .setContentText("Mikrofon a offline překlad jsou aktivní").setOngoing(true)
            .setContentIntent(open).addAction(0, "STOP", stop).build()
        try {
            if (Build.VERSION.SDK_INT >= 30) startForeground(1, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(1, notification)
        } catch (_: Exception) {
            PrimeState.error("FGS_START", "Poslech spusťte v otevřené aplikaci s povoleným mikrofonem.")
            stopSelf()
            return START_NOT_STICKY
        }
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { change ->
                focusGranted = change == AudioManager.AUDIOFOCUS_GAIN
                updateGate()
            }.setWillPauseWhenDucked(true).build()
        focus = request
        focusGranted = audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!focusGranted) {
            PrimeState.error("AUDIO_FOCUS", "Zvuk nyní používá hovor nebo jiná aplikace. Zkuste START později.")
            stopSelf()
            return START_NOT_STICKY
        }
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "primevoice:session")
            .also { it.acquire(6 * 60 * 60 * 1000L) }
        val engine = PrimePipeline(applicationContext)
        pipeline = engine
        updateRoute()
        work = scope.launch {
            try { engine.run(intent?.data) }
            catch (e: CancellationException) { throw e }
            catch (_: OutOfMemoryError) { PrimeState.error("LOW_MEMORY", "Nedostatek paměti. Uložená fronta zůstává zachována.") }
            catch (_: Exception) { PrimeState.error("PIPELINE_START", "Překlad se zastavil. Zkontrolujte modely, český hlas a oprávnění; exportujte diagnostiku.") }
            finally { engine.release(); stopSelf() }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        pipeline?.stop()
        work?.cancel()
        // Keep the scope alive until the native workers finish their finally/release blocks.
        work?.invokeOnCompletion { scope.cancel() } ?: scope.cancel()
        focus?.let(audio::abandonAudioFocusRequest)
        audio.unregisterAudioDeviceCallback(devices)
        unregisterReceiver(noisy)
        wakeLock?.let { if (it.isHeld) it.release() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?) = null
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_COMPLETE) {
            PrimeState.error("LOW_MEMORY", "Android hlásí kritický nedostatek paměti; poslech se zastavuje.")
            stopSelf()
        }
    }
}
