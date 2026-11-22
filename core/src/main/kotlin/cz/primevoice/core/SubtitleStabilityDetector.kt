package cz.primevoice.core

import java.util.Locale
import kotlin.math.max

data class OcrReading(val text: String, val at: Long, val confidence: Float = 0.85f)
data class Subtitle(val text: String, val capturedAt: Long, val completedAt: Long,
                    val confidence: Float, val reason: String)

object TextRules {
    fun clean(text: String): String = text.replace('’', '\'').replace('“', '"').replace('”', '"')
        .replace(Regex("[\\r\\n]+"), " ").replace(Regex("\\s+"), " ").trim()
    fun key(text: String): String = clean(text).lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}' ]"), "").replace(Regex("\\s+"), " ").trim()
    fun terminal(text: String): Boolean = Regex("[.!?][\\\"')]*$").containsMatchIn(text) &&
        !text.endsWith("...") && !Regex("(?i)\\b(mr|mrs|ms|dr|st|vs)\\.$").containsMatchIn(text)
    fun similarity(a: String, b: String): Float {
        if (a == b) return 1f
        if (a.isEmpty() || b.isEmpty()) return 0f
        var previous = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val next = IntArray(b.length + 1); next[0] = i + 1
            for (j in b.indices) next[j + 1] = minOf(next[j] + 1, previous[j + 1] + 1,
                previous[j] + if (a[i] == b[j]) 0 else 1)
            previous = next
        }
        return 1f - previous[b.length].toFloat() / max(a.length, b.length)
    }
}

/** Session-local temporal assembler. Times are monotonic milliseconds, never wall clock. */
class SubtitleStabilityDetector {
    private data class Candidate(var text: String, val first: Long, var changed: Long,
                                 var seen: Long, var frames: Int, var confidence: Float,
                                 var emitted: Boolean = false)
    private var candidate: Candidate? = null
    private var pending: Subtitle? = null
    private var pendingUntil = 0L
    private var typicalChangeMs = 250L
    private var missingSince: Long? = null
    private val recent = ArrayDeque<Pair<String, Long>>()
    var duplicates: Int = 0; private set

    fun reset() {
        candidate = null; pending = null; recent.clear(); missingSince = null
        typicalChangeMs = 250L; duplicates = 0; continuationPrefix = ""; spokenPrefix = ""
    }

    fun accept(reading: OcrReading, alignedSpeechEnded: Boolean = false): List<Subtitle> {
        val out = mutableListOf<Subtitle>()
        val now = reading.at
        val raw = TextRules.clean(reading.text).take(900)
        while (recent.isNotEmpty() && now - recent.first().second > 18_000) recent.removeFirst()
        if (raw.isBlank() || reading.confidence < 0.35f) {
            if (missingSince == null) missingSince = now
            candidate?.let { c ->
                if (now - missingSince!! >= 600 && !c.emitted && c.frames >= 2) {
                    finish(c, now, "titulek zmizel", out); c.emitted = true
                }
            }
            if (now - missingSince!! > 1800) candidate = null
            flushPending(now, out)
            return out
        }
        missingSince = null
        val c = candidate
        val same = c != null && TextRules.key(raw) == TextRules.key(c.text)
        if (same) {
            c!!.frames++; c.seen = now
            // Better punctuation/quality may replace an OCR variant, but never resets stability.
            if (reading.confidence >= c.confidence || TextRules.terminal(raw)) c.text = raw
            c.confidence = max(c.confidence, reading.confidence)
        } else if (c != null && !c.emitted && isRevision(c.text, raw)) {
            typicalChangeMs = ((typicalChangeMs * 3 + (now - c.changed).coerceIn(80, 900)) / 4)
            c.text = raw; c.changed = now; c.seen = now; c.frames = 1; c.confidence = reading.confidence
        } else {
            if (c != null && !c.emitted && c.frames >= 2) finish(c, now, "nový blok", out)
            // A progressive subtitle can append after the previous part has already been spoken.
            val text = if (c?.emitted == true && raw.startsWith(c.text, ignoreCase = true)) {
                spokenPrefix = TextRules.clean(spokenPrefix + " " + c.text)
                raw.drop(c.text.length).trim()
            } else raw
            candidate = Candidate(text, now, now, now, 1, reading.confidence)
            pending?.let { p ->
                if (now <= pendingUntil && canContinue(p.text, text)) {
                    candidate = Candidate(p.text.trimEnd('.', '…') + " " + text, p.capturedAt,
                        now, now, 1, minOf(p.confidence, reading.confidence))
                    // Keep physical screen text separately so continued snapshots remain stable.
                    continuationPrefix = p.text.trimEnd('.', '…') + " "
                    pending = null
                } else {
                    emit(p, out); pending = null; continuationPrefix = ""
                }
            }
        }
        val active = candidate
        if (active != null && !active.emitted && active.text.isNotBlank()) {
            val stable = now - active.changed
            val terminal = TextRules.terminal(active.text)
            val hold = if (terminal) (typicalChangeMs * 2 + 350).coerceIn(650, 1300)
                else (typicalChangeMs * 3 + 1000).coerceIn(1500, 2600)
            val assistedHold = if (alignedSpeechEnded) max(500L, hold - 150) else hold
            if ((active.frames >= 3 && stable >= assistedHold) ||
                (active.frames >= 2 && now - active.first >= 5000 && stable >= 450)) {
                finish(active, now, if (terminal) "stabilní věta" else "časový limit", out)
                active.emitted = true
            }
        }
        flushPending(now, out)
        return out
    }

    private var continuationPrefix = ""
    private var spokenPrefix = ""

    /** Apply pending cross-screen prefix before comparing subsequent frames. */
    fun observe(reading: OcrReading, speechEnded: Boolean = false): List<Subtitle> {
        val c = candidate
        var adjusted = reading
        var clean = TextRules.clean(reading.text)
        if (spokenPrefix.isNotEmpty()) {
            if (clean.startsWith(spokenPrefix, ignoreCase = true)) {
                clean = clean.drop(spokenPrefix.length).trim()
                adjusted = reading.copy(text = clean)
            } else if (clean.isNotBlank()) spokenPrefix = ""
        }
        if (continuationPrefix.isNotEmpty() && c != null) {
            val tail = c.text.removePrefix(continuationPrefix)
            if (clean.isNotBlank() && (TextRules.key(clean) == TextRules.key(tail) || isRevision(tail, clean)))
                return accept(adjusted.copy(text = continuationPrefix + clean), speechEnded)
            continuationPrefix = ""
        }
        return accept(adjusted, speechEnded)
    }

    private fun isRevision(old: String, new: String): Boolean {
        val a = TextRules.key(old); val b = TextRules.key(new)
        return (a.length >= 5 && b.startsWith(a + " ")) ||
            (a.length >= 18 && TextRules.similarity(a, b) > 0.91f)
    }

    private fun finish(c: Candidate, now: Long, reason: String, out: MutableList<Subtitle>) {
        val block = Subtitle(c.text, c.first, now, c.confidence, reason)
        if (TextRules.terminal(c.text)) {
            pending?.let { emit(it, out) }; pending = null
            emit(block, out)
        } else {
            pending?.let { emit(it, out) }
            pending = block; pendingUntil = now + 1000
        }
    }

    private fun canContinue(a: String, b: String): Boolean =
        !TextRules.terminal(a) && b.isNotBlank() && a.length + b.length < 800 &&
            (b.first().isLowerCase() || Regex("(?i)^(and|but|or|because|so|that|which|to)\\b").containsMatchIn(b))

    private fun flushPending(now: Long, out: MutableList<Subtitle>) {
        pending?.let { if (now >= pendingUntil) { emit(it, out); pending = null } }
    }
    private fun emit(block: Subtitle, out: MutableList<Subtitle>) {
        val key = TextRules.key(block.text)
        if (key.isBlank()) return
        if (recent.any { it.first == key }) { duplicates++; return }
        recent.addLast(key to block.completedAt); out.add(block)
    }
}
