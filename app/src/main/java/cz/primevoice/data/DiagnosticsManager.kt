package cz.primevoice.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class Diagnostics(val ocrMs: Long = 0, val translationMs: Long = 0, val ttsPreparationMs: Long = 0, val actualLatencyMs: Long = 0,
                       val captured: Int = 0, val duplicates: Int = 0, val ocrCount: Int = 0,
                       val skippedFrames: Int = 0, val confidence: Float = 0f,
                       val fusion: String = "kamera", val errors: List<String> = emptyList())
class DiagnosticsManager {
    private val mutable = MutableStateFlow(Diagnostics())
    val state = mutable.asStateFlow()
    fun update(block: (Diagnostics) -> Diagnostics) = mutable.update(block)
    fun error(message: String) = mutable.update { it.copy(errors = (it.errors + message).takeLast(8)) }
}
