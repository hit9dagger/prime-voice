package cz.primevoice.engine

import java.io.Closeable
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.SpeakerModel

data class RecognitionResult(
    val text: String, val embedding: FloatArray?, val speakerFrames: Int,
    val sourceStartSeconds: Double? = null, val sourceEndSeconds: Double? = null
)

/** Real Vosk endpoints and optional embeddings; does not separate overlapping speakers. */
class Recognition(models: Models, useSpeakers: Boolean) : Closeable {
    private val model: Model
    private val speaker: SpeakerModel?
    private var recognizer: Recognizer
    private var closed = false
    private var finished = false
    init {
        require(models.asrReady()) { "Nejprve stáhněte anglický model." }
        require(!useSpeakers || models.speakerReady()) { "Nejprve stáhněte model mluvčích." }
        val loadedModel = Model(models.asrDirectory.absolutePath)
        var loadedSpeaker: SpeakerModel? = null
        var loadedRecognizer: Recognizer? = null
        try {
            if (useSpeakers) loadedSpeaker = SpeakerModel(models.speakerDirectory.absolutePath)
            loadedRecognizer = if (loadedSpeaker != null) Recognizer(loadedModel, 16_000f, loadedSpeaker)
                else Recognizer(loadedModel, 16_000f)
            loadedRecognizer.setWords(true)
            model = loadedModel
            speaker = loadedSpeaker
            recognizer = loadedRecognizer
        } catch (failure: Throwable) {
            loadedRecognizer?.close(); loadedSpeaker?.close(); loadedModel.close()
            throw failure
        }
    }
    @Synchronized fun accept(pcm: ShortArray, count: Int): RecognitionResult? {
        check(!closed) { "Rozpoznávání bylo ukončeno." }
        check(!finished) { "Po finish() nejprve zavolejte reset()." }
        require(count in 0..pcm.size) { "Neplatná délka zvuku." }
        if (count == 0) return null
        return if (recognizer.acceptWaveForm(pcm, count)) parse(recognizer.result) else null
    }
    @Synchronized fun finish(): RecognitionResult? {
        check(!closed) { "Rozpoznávání bylo ukončeno." }
        if (finished) return null
        finished = true
        return parse(recognizer.finalResult)
    }
    @Synchronized fun reset() {
        check(!closed) { "Rozpoznávání bylo ukončeno." }
        // Native reset() retains word-time offsets. Recreate only the lightweight recognizer
        // so callers can establish a new monotonic origin after a real input gap.
        val replacement = if (speaker != null) Recognizer(model, 16_000f, speaker) else Recognizer(model, 16_000f)
        try { replacement.setWords(true) } catch (failure: Throwable) { replacement.close(); throw failure }
        val previous = recognizer
        recognizer = replacement
        finished = false
        previous.close()
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        try { recognizer.close() } finally { try { speaker?.close() } finally { model.close() } }
    }
    private fun parse(json: String): RecognitionResult? {
        val result = JSONObject(json)
        val text = result.optString("text").trim()
        if (text.isBlank()) return null
        val vector = result.optJSONArray("spk")?.let { array ->
            if (array.length() == 0 || array.length() > 4096) null
            else FloatArray(array.length()) { array.getDouble(it).toFloat() }
                .takeIf { values -> values.all { it.isFinite() } }
        }
        val words = result.optJSONArray("result")
        val start = if (words != null && words.length() > 0)
            words.getJSONObject(0).optDouble("start").takeIf { it.isFinite() && it >= 0.0 } else null
        val end = if (words != null && words.length() > 0)
            words.getJSONObject(words.length() - 1).optDouble("end").takeIf { it.isFinite() && it >= (start ?: 0.0) } else null
        return RecognitionResult(text, vector, result.optInt("spk_frames", 0).coerceAtLeast(0), start, end)
    }
}
