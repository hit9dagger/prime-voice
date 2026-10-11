package cz.primevoice

import android.app.ActivityManager
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

data class Line(
    val id: String, val speaker: String, val sourceStart: Long, val sourceEnd: Long,
    val english: String, val czech: String, val voice: String, val status: String = "Ve frontě"
)
data class PrimeUi(
    val active: Boolean = false, val status: String = "Připraveno",
    val error: String = "", val modelProgress: String = "", val modelsReady: Boolean = false,
    val level: Float = 0f, val inputRouteType: Int = -1, val backlog: Int = 0, val missedBlocks: Long = 0,
    val asrMs: Long = 0, val translationMs: Long = 0, val synthesisMs: Long = 0,
    val latencyMs: Long = 0, val engine: String = "", val route: String = "",
    val voices: List<Pair<String, String>> = emptyList(), val lines: List<Line> = emptyList(),
    val speakerVoices: Map<String, String> = emptyMap(),
    val characterVoice: Boolean = false, val volume: Float = 0.8f, val speed: Float = 1f
)
object PrimeState {
    val ui = MutableStateFlow(PrimeUi())
    private val errors = ArrayDeque<Pair<Long, String>>()
    fun error(code: String, description: String) {
        synchronized(errors) {
            if (errors.size >= 30) errors.removeFirst()
            errors.addLast(SystemClock.elapsedRealtime() to code)
        }
        ui.update { it.copy(error = "$code: $description") }
    }
    fun line(line: Line) = ui.update { state ->
        state.copy(lines = (state.lines.filterNot { it.id == line.id } + line).takeLast(30))
    }
    fun lineStatus(id: String, status: String) = ui.update { state ->
        state.copy(lines = state.lines.map { if (it.id == id) it.copy(status = status) else it })
    }
    fun diagnostics(context: Context): String {
        val state = ui.value
        val memory = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        val runtime = Runtime.getRuntime()
        val pm = context.getSystemService(PowerManager::class.java)
        val audio = context.getSystemService(AudioManager::class.java)
        val devices = JSONArray()
        (audio.getDevices(AudioManager.GET_DEVICES_INPUTS) + audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)).distinctBy { it.id }.forEach {
            devices.put(JSONObject().put("type", it.type).put("input", it.isSource).put("output", it.isSink))
        }
        val events = synchronized(errors) {
            JSONArray(errors.map { JSONObject().put("elapsedMs", it.first).put("code", it.second) })
        }
        return JSONObject()
            .put("schema", 1).put("app", "PRIME VOICE").put("sdk", Build.VERSION.SDK_INT)
            .put("elapsedMs", SystemClock.elapsedRealtime()).put("active", state.active)
            .put("recordAudioGranted", context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED)
            .put("actualInputDeviceType", state.inputRouteType).put("inputRms", state.level).put("modelsReady", state.modelsReady)
            .put("queueSize", state.backlog).put("missedInputBlocks", state.missedBlocks)
            .put("asrMs", state.asrMs).put("translationMs", state.translationMs)
            .put("synthesisMs", state.synthesisMs).put("toPlaybackMs", state.latencyMs)
            .put("heapUsedBytes", runtime.totalMemory() - runtime.freeMemory())
            .put("availableSystemBytes", memory.availMem).put("lowMemory", memory.lowMemory)
            .put("thermalStatus", if (Build.VERSION.SDK_INT >= 29) pm.currentThermalStatus else -1)
            .put("audioDevices", devices).put("errors", events)
            .put("privacy", "No audio, transcripts, translations, voice profiles or device identifiers included.")
            .toString(2)
    }
}
