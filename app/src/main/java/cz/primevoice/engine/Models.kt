package cz.primevoice.engine

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** HTTPS + published size/MD5 detect transfer corruption. MD5 is not publisher authentication. */
class Models(context: Context) {
    private val root = File(context.applicationContext.filesDir, "models")
    val asrDirectory: File get() = File(root, ASR.id)
    val speakerDirectory: File get() = File(root, SPEAKER.id)
    fun asrReady(): Boolean = ready(ASR)
    fun speakerReady(): Boolean = ready(SPEAKER)
    suspend fun downloadAsr(progress: (String) -> Unit) = install(ASR, progress)
    suspend fun downloadSpeaker(progress: (String) -> Unit) = install(SPEAKER, progress)
    private fun ready(spec: Spec): Boolean = try {
        val directory = File(root, spec.id)
        File(directory, ".complete").readText() == spec.md5 &&
            spec.required.all { File(directory, it).let { file -> file.isFile && file.length() > 0 } }
    } catch (_: IOException) { false }

    private suspend fun install(spec: Spec, progress: (String) -> Unit) = withContext(Dispatchers.IO) {
        installLock.withLock {
            if (ready(spec)) { progress("Model je připraven."); return@withLock }
            check(root.isDirectory || root.mkdirs()) { "Nelze vytvořit úložiště modelů." }
            require(root.usableSpace >= spec.bytes + spec.maxExtracted + 16L * 1024 * 1024) {
                "Nedostatek místa. Uvolněte alespoň 300 MB pro instalaci modelů."
            }
            val work = File(root, ".install-" + UUID.randomUUID().toString())
            check(work.mkdir()) { "Nelze vytvořit pracovní adresář." }
            try {
                val archive = File(work, "model.zip")
                download(spec, archive, progress)
                currentCoroutineContext().ensureActive()
                progress("Ověřuji a rozbaluji model…")
                val unpacked = File(work, "unpacked")
                check(unpacked.mkdir())
                unpack(spec, archive, unpacked)
                val candidate = File(unpacked, spec.id)
                require(spec.required.all {
                    File(candidate, it).let { file -> file.isFile && file.length() > 0 }
                }) { "Archiv neobsahuje úplný model." }
                File(candidate, ".complete").writeText(spec.md5)
                currentCoroutineContext().ensureActive()
                val destination = File(root, spec.id)
                if (destination.exists() && !destination.deleteRecursively()) throw IOException("Nelze odstranit neúplný model.")
                if (!candidate.renameTo(destination)) throw IOException("Instalace modelu selhala.")
                progress("Model je připraven.")
            } finally { work.deleteRecursively() }
        }
    }
    private suspend fun download(spec: Spec, archive: File, progress: (String) -> Unit) {
        var url = URL("https://alphacephei.com/vosk/models/" + spec.id + ".zip")
        var redirects = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            require(url.protocol == "https" && url.host == "alphacephei.com" &&
                (url.port == -1 || url.port == 443) && url.userInfo == null) { "Neplatný server modelů." }
            val connection = url.openConnection() as HttpsURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept-Encoding", "identity")
            try {
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    require(++redirects <= 5) { "Příliš mnoho přesměrování." }
                    val location = connection.getHeaderField("Location") ?: throw IOException("Chybí cíl přesměrování.")
                    url = URL(url, location)
                    continue
                }
                if (status != 200) throw IOException("Stažení modelu: HTTP " + status)
                val advertised = connection.contentLengthLong
                require(advertised == -1L || advertised == spec.bytes) { "Velikost modelu se změnila." }
                val digest = MessageDigest.getInstance("MD5")
                var received = 0L
                var lastPercent = -1
                val buffer = ByteArray(32 * 1024)
                connection.inputStream.buffered().use { input ->
                    archive.outputStream().buffered().use { output ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            received += count
                            require(received <= spec.bytes) { "Model je větší než očekávaná velikost." }
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                            val percent = (received * 100 / spec.bytes).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                progress(spec.id + ": " + percent + " % (" + (spec.bytes / 1_000_000) + " MB)")
                            }
                        }
                    }
                }
                val actualHash = digest.digest().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
                require(received == spec.bytes && actualHash == spec.md5) { "Kontrola integrity modelu selhala. Zkuste stažení znovu." }
                return
            } finally { connection.disconnect() }
        }
    }
    private suspend fun unpack(spec: Spec, archive: File, destination: File) {
        val prefix = destination.canonicalPath + File.separator
        var total = 0L
        var entries = 0
        val names = HashSet<String>()
        val buffer = ByteArray(32 * 1024)
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = zip.nextEntry ?: break
                require(++entries <= 2048) { "Příliš mnoho souborů v modelu." }
                val name = entry.name
                val pieces = name.split('/')
                require(name.isNotEmpty() && !name.contains('\\') &&
                    pieces.first() == spec.id && pieces.none { it == ".." || it == "." } &&
                    names.add(name)) { "Neplatná cesta v archivu modelu." }
                val target = File(destination, name)
                require(target.canonicalPath.startsWith(prefix)) { "Neplatná cesta v archivu." }
                if (entry.isDirectory) {
                    check(target.isDirectory || target.mkdirs()) { "Nelze vytvořit adresář modelu." }
                } else {
                    check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
                    target.outputStream().buffered().use { output ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = zip.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            total += count
                            require(total <= spec.maxExtracted) { "Rozbalený model je příliš velký." }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
    }
    private data class Spec(val id: String, val bytes: Long, val md5: String, val maxExtracted: Long, val required: List<String>)
    companion object {
        private val installLock = Mutex()
        private val ASR = Spec("vosk-model-small-en-us-0.15", 41_205_931L,
            "09ab50ccd62b674cbaa231b825f9c1cb", 200L * 1024 * 1024,
            listOf("am/final.mdl", "conf/model.conf", "conf/mfcc.conf", "graph/HCLr.fst", "graph/Gr.fst"))
        private val SPEAKER = Spec("vosk-model-spk-0.4", 13_869_103L,
            "63b868d866a756c30f048b007541fd25", 64L * 1024 * 1024,
            listOf("mfcc.conf", "final.ext.raw", "mean.vec", "transform.mat"))
    }
}
