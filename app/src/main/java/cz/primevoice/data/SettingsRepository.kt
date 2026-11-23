package cz.primevoice.data

import android.content.Context
import cz.primevoice.core.Region
import cz.primevoice.core.ExecutionMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Settings(val mode: ExecutionMode = ExecutionMode.LIGHT, val wifiOnly: Boolean = true, val region: Region = Region(), val delaySeconds: Int = 2,
                    val microphone: Boolean = false, val engine: String = "", val voice: String = "",
                    val rate: Float = 1f, val pitch: Float = 1f, val zoom: Float = 1f,
                    val exposure: Int = 0, val enhance: Boolean = false, val names: String = "")

/** Preferences contain settings only; frames, audio, subtitles and translations are never written. */
class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("prime_voice_settings", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(Settings(
        mode = runCatching { ExecutionMode.valueOf(prefs.getString("mode", "LIGHT") ?: "LIGHT") }.getOrDefault(ExecutionMode.LIGHT),
        wifiOnly = prefs.getBoolean("wifiOnly", true),
        region = Region(prefs.getFloat("left", .08f), prefs.getFloat("top", .65f),
            prefs.getFloat("right", .92f), prefs.getFloat("bottom", .94f)).valid(),
        delaySeconds = prefs.getInt("delay", 2).coerceIn(0, 10),
        microphone = prefs.getBoolean("microphone", false), engine = prefs.getString("engine", "") ?: "",
        voice = prefs.getString("voice", "") ?: "", rate = prefs.getFloat("rate", 1f).coerceIn(.75f, 1.5f),
        pitch = prefs.getFloat("pitch", 1f).coerceIn(.75f, 1.25f), zoom = prefs.getFloat("zoom", 1f),
        exposure = prefs.getInt("exposure", 0), enhance = prefs.getBoolean("enhance", false),
        names = prefs.getString("names", "") ?: ""))
    val state = mutable.asStateFlow()
    fun update(transform: (Settings) -> Settings) {
        val s = transform(mutable.value); mutable.value = s
        prefs.edit().putBoolean("wifiOnly", s.wifiOnly).putString("mode", s.mode.name).putFloat("left", s.region.left).putFloat("top", s.region.top)
            .putFloat("right", s.region.right).putFloat("bottom", s.region.bottom)
            .putInt("delay", s.delaySeconds).putBoolean("microphone", s.microphone)
            .putString("engine", s.engine).putString("voice", s.voice).putFloat("rate", s.rate)
            .putFloat("pitch", s.pitch).putFloat("zoom", s.zoom).putInt("exposure", s.exposure)
            .putBoolean("enhance", s.enhance).putString("names", s.names).apply()
    }
}
