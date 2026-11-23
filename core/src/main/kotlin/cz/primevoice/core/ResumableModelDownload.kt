package cz.primevoice.core

import kotlinx.coroutines.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Immutable URLs plus full SHA-256 validation; partial bytes never become an active model. */
class ResumableModelDownload {
    @Volatile private var connection: HttpURLConnection? = null
    fun disconnect() { connection?.disconnect() }
    suspend fun download(url: String, part: File, destination: File, expectedBytes: Long, sha256: String,
        beforeChunk: () -> Unit = {}, progress: (Long) -> Unit = {}) = withContext(Dispatchers.IO) {
        require(expectedBytes > 0)
        if (part.length() > expectedBytes) check(part.delete()) { "Neplatný rozpracovaný soubor nelze odstranit." }
        var offset = part.length()
        if (offset < expectedBytes) {
            beforeChunk()
            val http = URL(url).openConnection() as HttpURLConnection
            connection = http
            http.connectTimeout = 15_000; http.readTimeout = 8_000
            http.setRequestProperty("Accept-Encoding", "identity")
            if (offset > 0) http.setRequestProperty("Range", "bytes=$offset-")
            try {
                when (http.responseCode) {
                    200 -> offset = 0 // Server ignored Range: start afresh, never append two copies.
                    206 -> {
                        val range = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(http.getHeaderField("Content-Range").orEmpty())
                        check(range != null && range.groupValues[1].toLong() == offset &&
                            range.groupValues[3].toLong() == expectedBytes) { "Server vrátil chybný rozsah souboru. Obnovení bylo zastaveno." }
                    }
                    else -> error("Stažení modelu: HTTP ${http.responseCode}. Rozpracovaná data zůstala zachována.")
                }
                var written = offset; var lastReported = offset
                progress(written)
                http.inputStream.use { input ->
                    java.io.FileOutputStream(part, offset > 0).buffered().use { output ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            ensureActive()
                            val n = input.read(buffer); if (n < 0) break
                            check(written + n <= expectedBytes) { "Server posílá větší soubor, než odpovídá modelu." }
                            output.write(buffer, 0, n); written += n
                            if (written - lastReported >= MIB) { beforeChunk(); progress(written); lastReported = written }
                        }
                    }
                }
                check(written == expectedBytes) { "Stahování se přerušilo. Použijte Obnovit stahování." }
            } finally { connection = null; http.disconnect() }
        }
        progress(expectedBytes)
        if (!verifyFile(part, expectedBytes, sha256)) {
            part.delete()
            error("Nesouhlasí SHA-256 modelu. Neplatný soubor byl odstraněn; stáhněte jej znovu.")
        }
        ensureActive()
        check(part.renameTo(destination)) { "Ověřený model nelze uložit." }
    }
}

suspend fun verifyFile(file: File, expectedBytes: Long, expectedHash: String): Boolean = withContext(Dispatchers.IO) {
    if (!file.isFile || file.length() != expectedBytes) return@withContext false
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(128 * 1024)
        while (true) { ensureActive(); val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
    }
    digest.digest().joinToString("") { "%02x".format(it) }.equals(expectedHash, ignoreCase = true)
}
