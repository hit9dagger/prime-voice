package cz.primevoice

import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

/** App-private journal. A permit is reserved before translation and released after playback/ack. */
class PendingStore(private val directory: File) {
    private val sequences = mutableMapOf<String, Long>()
    private var nextSequence = 0L
    private var loaded = false
    init {
        check(directory.exists() || directory.mkdirs())
        directory.listFiles().orEmpty().filter { it.extension == "wav" }.forEach { wav ->
            val journal = File(directory, wav.nameWithoutExtension + ".json")
            if (!journal.exists() && !File(journal.path + ".bak").exists() && !wav.delete())
                PrimeState.error("QUEUE_CLEANUP", "Nepodařilo se odstranit zbytkový zvuk fronty.")
        }
    }
    @Synchronized fun readAll(): List<Line> {
        val files = directory.listFiles().orEmpty()
            .filter { it.name.endsWith(".json") || it.name.endsWith(".json.bak") }
            .map { if (it.name.endsWith(".bak")) File(it.path.removeSuffix(".bak")) else it }
            .distinctBy { it.path }
        val records = files.map { file ->
            val atomic = AtomicFile(file)
            val j = atomic.openRead().use { stream ->
                require(stream.channel.size() <= 100_000) { "QUEUE_CORRUPT" }
                JSONObject(stream.bufferedReader().readText())
            }
            val id = j.getString("id")
            require(id.matches(Regex("[0-9a-fA-F-]{36}"))) { "QUEUE_CORRUPT" }
            val seq = j.optLong("sequence", file.lastModified().coerceAtLeast(0))
            sequences[id] = seq
            seq to Line(id, j.getString("speaker"), j.getLong("sourceStart"),
                j.getLong("sourceEnd"), j.getString("english"), j.getString("czech"),
                j.optString("voice"), "Obnovená fronta — čeká na přehrání")
        }
        nextSequence = (records.maxOfOrNull { it.first } ?: -1L) + 1L
        loaded = true
        return records.sortedBy { it.first }.map { it.second }
    }
    @Synchronized fun save(line: Line) {
        require(line.english.length <= 4000 && line.czech.length <= 8000)
        if (!loaded) readAll()
        val sequence = sequences.getOrPut(line.id) { nextSequence++ }
        val j = JSONObject().put("id", line.id).put("sequence", sequence).put("speaker", line.speaker)
            .put("sourceStart", line.sourceStart).put("sourceEnd", line.sourceEnd)
            .put("english", line.english).put("czech", line.czech).put("voice", line.voice).put("state", "queued")
        val file = AtomicFile(File(directory, line.id + ".json"))
        val stream = file.startWrite()
        try { stream.write(j.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (e: Throwable) { file.failWrite(stream); throw e }
    }
    fun audio(line: Line) = File(directory, line.id + ".wav")
    @Synchronized fun complete(line: Line) {
        // Caller remembers successful playback and retries only this acknowledgment on failure.
        val wav = audio(line)
        check(!wav.exists() || wav.delete()) { "QUEUE_AUDIO_DELETE" }
        val journal = File(directory, line.id + ".json")
        val atomic = AtomicFile(journal)
        atomic.delete()
        check(!journal.exists() && !File(journal.path + ".bak").exists() && !File(journal.path + ".new").exists()) { "QUEUE_DELETE" }
        sequences.remove(line.id)
    }
    @Synchronized fun clear() {
        directory.listFiles().orEmpty().forEach { check(it.delete()) }
        sequences.clear(); loaded = false
    }
    fun hasSpace() = directory.usableSpace > 96L * 1024 * 1024
}
