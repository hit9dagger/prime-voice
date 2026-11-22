package cz.primevoice.core

data class SpeechEvidence(val text: String, val startedAt: Long, val endedAt: Long,
                          val confidence: Float, val final: Boolean)
data class FusionResult(val text: String, val confidence: Float, val alignedEnd: Boolean,
                        val explanation: String)

/** ASR never creates a subtitle. Only a temporally aligned final result may repair uncertain OCR. */
class MultimodalFusionEngine {
    private val samples = ArrayDeque<SpeechEvidence>()
    fun add(sample: SpeechEvidence) {
        samples.addLast(sample)
        while (samples.size > 8) samples.removeFirst()
    }
    fun clear() = samples.clear()
    fun fuse(ocr: OcrReading): FusionResult {
        val base = FusionResult(ocr.text, ocr.confidence, false, "kamera")
        val s = samples.lastOrNull { it.final && it.confidence >= 0.8f &&
            ocr.at >= it.startedAt - 700 && ocr.at <= it.endedAt + 1600 } ?: return base
        val a = ocr.text.split(Regex("\\s+")); val b = s.text.split(Regex("\\s+"))
        val ka = a.map(TextRules::key); val kb = b.map(TextRules::key)
        val overlap = ka.count { it in kb }.toFloat() / maxOf(ka.size, kb.size, 1)
        if (overlap < 0.70f || a.size < 4) return base.copy(explanation = "nesoulad: přednost má kamera")
        if (ka == kb) return base.copy(confidence = maxOf(ocr.confidence, 0.93f),
            alignedEnd = true, explanation = "shoda kamery a řeči")
        if (ocr.confidence >= 0.70f) return base.copy(explanation = "kamera má vyšší jistotu")
        if (a.size == b.size) {
            val differences = a.indices.filter { ka[it] != kb[it] }
            if (differences.size == 1) {
                val i = differences.single()
                val visiblyBroken = a[i].contains('�') || Regex("[a-zA-Z][0-9]|[0-9][a-zA-Z]").containsMatchIn(a[i])
                if (visiblyBroken && i > 0 && i < a.lastIndex && ka[i - 1] == kb[i - 1] && ka[i + 1] == kb[i + 1]) {
                    val patched = a.toMutableList(); patched[i] = b[i]
                    return FusionResult(patched.joinToString(" "), 0.85f, true, "opraven poškozený OCR token")
                }
            }
        }
        // At most one missing interior word; never insert negations or rewrite plausible camera words.
        if (b.size == a.size + 1 && s.confidence >= 0.9f && ocr.confidence < 0.60f) {
            val i = (2 until b.lastIndex - 1).firstOrNull { idx ->
                kb.filterIndexed { index, _ -> index != idx } == ka &&
                    kb[idx] !in setOf("not", "never", "no", "don't", "can't", "won't")
            }
            if (i != null) {
                val patched = a.toMutableList(); patched.add(i, b[i])
                return FusionResult(patched.joinToString(" "), 0.82f, true, "doplněno slovo mezi shodnými kotvami")
            }
        }
        return base.copy(explanation = "odlišný dabing: zachován titulek")
    }
}
