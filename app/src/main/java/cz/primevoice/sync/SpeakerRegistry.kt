package cz.primevoice.sync
import kotlin.math.sqrt

/** Matches actual embeddings; short, invalid or ambiguous observations remain unknown. */
class SpeakerRegistry(
    private val maxSpeakers: Int = 8, private val minFrames: Int = 400,
    private val matchThreshold: Double = 0.75, private val newSpeakerThreshold: Double = 0.45,
    private val ambiguityMargin: Double = 0.10
) {
    companion object { const val UNKNOWN = "unknown"; private const val MAX_DIMENSIONS = 4096 }
    private data class Profile(val id: String, var centroid: DoubleArray, var observations: Int)
    private val profiles = mutableListOf<Profile>()
    private var dimensions: Int? = null
    init {
        require(maxSpeakers in 1..8)
        require(minFrames >= 400)
        require(matchThreshold.isFinite() && matchThreshold in 0.0..1.0)
        require(newSpeakerThreshold.isFinite() && newSpeakerThreshold in -1.0..matchThreshold)
        require(newSpeakerThreshold < matchThreshold)
        require(ambiguityMargin.isFinite() && ambiguityMargin > 0.0 && ambiguityMargin <= 1.0)
    }
    @Synchronized fun identify(embedding: FloatArray?, frames: Int): String {
        if (frames < minFrames || embedding == null) return UNKNOWN
        val vector = normalize(embedding) ?: return UNKNOWN
        if (dimensions != null && dimensions != vector.size) return UNKNOWN
        if (profiles.isEmpty()) return create(vector)
        val ranked = profiles.map { it to dot(it.centroid, vector) }.sortedByDescending { it.second }
        val best = ranked[0]
        val second = ranked.getOrNull(1)?.second ?: -1.0
        if (best.second >= matchThreshold) {
            if (ranked.size > 1 && best.second - second < ambiguityMargin) return UNKNOWN
            val weight = 1.0 / (best.first.observations.coerceAtMost(19) + 1)
            val updated = DoubleArray(vector.size) { best.first.centroid[it] * (1.0 - weight) + vector[it] * weight }
            val length = sqrt(updated.sumOf { it * it })
            if (!length.isFinite() || length < 1e-12) return UNKNOWN
            best.first.centroid = DoubleArray(updated.size) { updated[it] / length }
            best.first.observations = (best.first.observations + 1).coerceAtMost(20)
            return best.first.id
        }
        if (best.second < newSpeakerThreshold && profiles.size < maxSpeakers) return create(vector)
        return UNKNOWN
    }
    @Synchronized fun reset() { profiles.clear(); dimensions = null }
    private fun create(vector: DoubleArray): String {
        val id = "speaker-${profiles.size + 1}"
        dimensions = vector.size
        profiles += Profile(id, vector, 1)
        return id
    }
    private fun normalize(values: FloatArray): DoubleArray? {
        if (values.isEmpty() || values.size > MAX_DIMENSIONS) return null
        var squaredLength = 0.0
        for (value in values) {
            if (!value.isFinite()) return null
            squaredLength += value.toDouble() * value.toDouble()
        }
        if (!squaredLength.isFinite() || squaredLength < 1e-24) return null
        val length = sqrt(squaredLength)
        return DoubleArray(values.size) { values[it].toDouble() / length }
    }
    private fun dot(left: DoubleArray, right: DoubleArray): Double {
        var result = 0.0
        for (index in left.indices) result += left[index] * right[index]
        return result.coerceIn(-1.0, 1.0)
    }
}
