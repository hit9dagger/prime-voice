package cz.primevoice

import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

/**
 * Temporary app-private journal of completed translations. Entries are removed only after
 * successful playback, or an explicit user clear. Never included in backup or diagnostics.
 * Capacity is reserved BEFORE translation; filesystem failures stop admission.
 */
class PendingStore(private val directory: File) {
    init {
        check(directory.exists() || directory.mkdirs())
        // Never retain generated speech that no longer has a queue journal entry.
        directory.listFiles().orEmpty().filter { it.extension == "wav" }.forEach { wav ->
            val journal = File(directory, wav.nameWithoutExtension + ".json")
            if (!journal.exists() && !File(journal.path + ".bak").exists() && !wav.delete()) {
                PrimeState.error("QUEUE_CLEANUP", "Nepodařilo se odstranit zbytkový zvuk fronty.")
            }
        }
    }
    @Synchronized fun readAll(): List<Line> = directory.listFiles().orEmpty()
        .filter { it.extension == "json" }
        .map { file ->
            check(file.length() <= 100_000) { "QUEUE_CORRUPT" }
            val j = JSONObject(AtomicFile(file).openRead().bufferedReader().use { it.readText() })
            require(j.getString("id").matches(Regex("[0-9a-fA-F-]{36}"))) { "QUEUE_CORRUPT" }
            Line(j.getString("id"), j.getString("speaker"), j.getLong("sourceStart"),
                j.getLong("sourceEnd"), j.getString("english"), j.getString("czech"),
                j.optString("voice"), "Čeká na přehrání")
        }.sortedBy { it.sourceStart }
    @Synchronized fun save(line: Line) {
        require(line.english.length <= 4000 && line.czech.length <= 8000)
        val j = JSONObject().put("id", line.id).put("speaker", line.speaker)
            .put("sourceStart", line.sourceStart).put("sourceEnd", line.sourceEnd)
            .put("english", line.english).put("czech", line.czech).put("voice", line.voice)
        val file = AtomicFile(File(directory, line.id + ".json"))
        val stream = file.startWrite()
        try { stream.write(j.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (e: Throwable) { file.failWrite(stream); throw e }
    }
    fun audio(line: Line) = File(directory, line.id + ".wav")
    @Synchronized fun complete(line: Line) {
        val wav = audio(line)
        check(!wav.exists() || wav.delete()) { "QUEUE_AUDIO_DELETE" }
        check(File(directory, line.id + ".json").delete()) { "QUEUE_DELETE" }
    }
    @Synchronized fun clear() {
        directory.listFiles().orEmpty().forEach { check(it.delete()) }
    }
    fun hasSpace() = directory.usableSpace > 96L * 1024 * 1024
}
