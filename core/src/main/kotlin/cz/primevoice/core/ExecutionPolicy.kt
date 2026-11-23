package cz.primevoice.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

enum class ExecutionMode { LIGHT, STANDARD, ULTRA }
const val MIB = 1024L * 1024
const val GIB = 1024L * MIB

data class DeviceResources(val totalRam: Long = 0, val availableRam: Long = 0,
    val freeStorage: Long = 0, val lowRamDevice: Boolean = true, val lowMemory: Boolean = false,
    val nativeEngineSupported: Boolean = false)

data class CaptureBudget(val width: Int, val height: Int, val cropWidth: Int,
    val intervalMs: Long, val unchangedIntervalMs: Long)

/** Conservative admission rules, not promises about speed or translation quality. */
object ExecutionPolicy {
    fun economical(d: DeviceResources) = d.lowRamDevice || d.totalRam < 5 * GIB
    fun underPressure(d: DeviceResources) = d.lowMemory || d.availableRam < 512 * MIB
    fun rejection(mode: ExecutionMode, d: DeviceResources, modelBytes: Long, installed: Boolean): String? {
        if (mode == ExecutionMode.LIGHT) return null
        if (economical(d)) return "Pro telefony se 4 GB RAM a méně je dostupný úsporný LIGHT."
        if (!d.nativeEngineSupported) return "Lokální inferenční engine není na tomto zařízení kompatibilní."
        val minTotal = if (mode == ExecutionMode.ULTRA) 7 * GIB else 5 * GIB
        val minAvailable = if (mode == ExecutionMode.ULTRA) 4 * GIB else 2 * GIB
        if (d.totalRam < minTotal) return "Tento model potřebuje více celkové RAM; používá se LIGHT."
        if (d.lowMemory || d.availableRam < minAvailable) return "Nedostatek volné RAM pro načtení modelu; používá se LIGHT."
        val needed = (if (installed) 0 else modelBytes) + 768 * MIB
        if (d.freeStorage < needed) return "Pro model a pracovní data není dost místa; používá se LIGHT."
        return null
    }
    fun recommend(d: DeviceResources, verified: Set<ExecutionMode>): ExecutionMode =
        listOf(ExecutionMode.ULTRA, ExecutionMode.STANDARD).firstOrNull {
            it in verified && rejection(it, d, 0, true) == null
        } ?: ExecutionMode.LIGHT

    fun capture(mode: ExecutionMode, d: DeviceResources): CaptureBudget =
        if (mode == ExecutionMode.LIGHT || economical(d) || underPressure(d))
            CaptureBudget(1280, 720, 960, 400, 1200)
        else CaptureBudget(1920, 1080, 1100, 300, 1000)
}

/** LIGHT serializes ML Kit/OCR on low-RAM phones. A single native model runs on its
 * own two-thread process; OCR continues at reduced cadence so long inference cannot
 * hide subsequent subtitles. Never two model generations or two OCR tasks at once. */
class InferenceGate {
    private val permit = Semaphore(1)
    private val nativePermit = Semaphore(1)
    private val mutable = MutableStateFlow(false)
    val heavy = mutable.asStateFlow()
    fun tryCamera() = permit.tryAcquire()
    fun releaseCamera() = permit.release()
    suspend fun <T> run(heavy: Boolean = false, block: suspend () -> T): T =
        if (!heavy) permit.withPermit { block() }
        else nativePermit.withPermit {
            mutable.value = true
            try { block() } finally { mutable.value = false }
        }
}

object LocalTranslationChecks {
    fun acceptable(source: String, translated: String): Boolean {
        val t = translated.trim()
        if (t.isEmpty() || t.length > maxOf(160, source.length * 4)) return false
        if (Regex("(?i)<think|</think|<\\|im_|\\[INST\\]|here is the translation").containsMatchIn(t)) return false
        if (source.split(' ').size > 3 && TextRules.key(source) == TextRules.key(t)) return false
        return t.none { it.code < 32 && it != '\n' && it != '\t' }
    }
    // Admission smoke test only; never presented as a guarantee of translation quality.
    fun passesProbe(index: Int, translated: String): Boolean {
        val t = TextRules.key(translated)
        return when (index) {
            0 -> "dveř" in t && Regex("\\bne(?:otev|otv)").containsMatchIn(t)
            1 -> "jason" in t && listOf("kde", "kam").any { it in t }
            else -> false
        }
    }
    val probes = listOf("Do not open the door.", "Jason, where are you?")
}
