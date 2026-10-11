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
    init { check(directory.exists() || directory.mkdirs()) }
    @Synchronized fun readAll(): List<Line> = directory.listFiles().orEmpty()
        .filter { it.extension == "json" }
        .map { file ->
            val j = JSONObject(AtomicFile(file).openRead().bufferedReader().use { it.readText() })
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
        check(File(directory, line.id + ".json").delete()) { "QUEUE_DELETE" }
        audio(line).delete()
    }
    @Synchronized fun clear() {
        directory.listFiles().orEmpty().forEach { check(it.delete()) }
    }
    fun hasSpace() = directory.usableSpace > 96L * 1024 * 1024
}
