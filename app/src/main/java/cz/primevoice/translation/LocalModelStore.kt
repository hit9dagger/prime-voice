package cz.primevoice.translation

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.StatFs
import cz.primevoice.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

data class ModelTransfer(val active: Boolean = false, val downloaded: Long = 0, val total: Long = 0,
    val message: String = "Velké modely nejsou součástí APK.")

class LocalModelStore(private val context: Context) {
    private val directory = File(context.noBackupFilesDir, "language_models").apply { mkdirs() }
    private val lock = Mutex()
    private val downloader = ResumableModelDownload()
    private val mutable = MutableStateFlow(ModelTransfer())
    val state = mutable.asStateFlow()
    fun file(spec: LocalModelSpec) = File(directory, spec.fileName)
    private fun part(spec: LocalModelSpec) = File(directory, spec.fileName + ".part")
    fun installed(spec: LocalModelSpec) = file(spec).length() == spec.bytes
    fun partialBytes(spec: LocalModelSpec) = part(spec).length()
    suspend fun verify(spec: LocalModelSpec) = verifyFile(file(spec), spec.bytes, spec.sha256)
    fun pause() = downloader.disconnect()
    suspend fun delete(spec: LocalModelSpec) = withContext(Dispatchers.IO) {
        lock.withLock {
            check(!file(spec).exists() || file(spec).delete()) { "Model nelze odstranit." }
            check(!part(spec).exists() || part(spec).delete()) { "Rozpracovaný model nelze odstranit." }
            mutable.value = ModelTransfer(message = "Model byl odstraněn. LIGHT zůstává dostupný.")
        }
    }
    suspend fun download(spec: LocalModelSpec, wifiOnly: Boolean) = withContext(Dispatchers.IO) {
        transfer(spec, resume = true) {
            downloader.download(requireNotNull(spec.url), part(spec), file(spec), spec.bytes, spec.sha256,
                beforeChunk = {
                    checkNetwork(wifiOnly)
                    check(StatFs(directory.path).availableBytes > 256 * MIB) { "Úložiště se zaplnilo. Stahování pozastaveno." }
                }, progress = { bytes -> mutable.update { it.copy(downloaded = bytes,
                    message = if (bytes == spec.bytes) "Ověřuji SHA-256 celého modelu…" else "Stahuji ${spec.name}…") } })
        }
    }
    suspend fun import(spec: LocalModelSpec, uri: Uri) = withContext(Dispatchers.IO) {
        transfer(spec, resume = false) {
            // Imports use a separate temporary file so a failed SAF selection does not destroy a resumable download.
            val temp = File(directory, spec.fileName + ".import")
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    temp.outputStream().buffered().use { output ->
                        val buffer = ByteArray(128 * 1024); var total = 0L; var reported = 0L
                        while (true) {
                            ensureActive(); val n = input.read(buffer); if (n < 0) break
                            total += n; check(total <= spec.bytes) { "Vybraný soubor neodpovídá modelu ${spec.name}." }
                            output.write(buffer, 0, n)
                            if (total - reported >= MIB) {
                                reported = total; mutable.update { it.copy(downloaded = total) }
                                check(StatFs(directory.path).availableBytes > 256 * MIB) { "Úložiště se zaplnilo." }
                            }
                        }
                    }
                } ?: error("Soubor nelze otevřít.")
                mutable.update { it.copy(message = "Ověřuji SHA-256 importovaného modelu…") }
                check(verifyFile(temp, spec.bytes, spec.sha256)) { "Nesouhlasí velikost nebo SHA-256. Vyberte přesnou GGUF variantu uvedenou v aplikaci." }
                check(temp.renameTo(file(spec))) { "Model nelze uložit." }
                part(spec).delete()
            } finally { temp.delete() }
        }
    }
    private suspend fun transfer(spec: LocalModelSpec, resume: Boolean, block: suspend () -> Unit) = lock.withLock {
        val offset = if (resume) partialBytes(spec).coerceAtMost(spec.bytes) else 0
        check(StatFs(directory.path).availableBytes >= spec.bytes - offset + 768 * MIB) {
            "Je potřeba ještě ${(spec.bytes - offset + 768 * MIB) / MIB} MiB volného místa včetně rezervy."
        }
        mutable.value = ModelTransfer(true, offset, spec.bytes, "Připravuji ${spec.name}…")
        try { block(); mutable.update { it.copy(message = "Soubor ověřen. Nyní spusťte test kompatibility a překladu.") } }
        catch (e: CancellationException) {
            mutable.update { it.copy(message = "Příprava pozastavena. Stažená data zůstala zachována; lze pokračovat.") }; throw e
        } catch (e: Exception) { mutable.update { it.copy(message = e.message ?: "Příprava modelu selhala.") }; throw e }
        finally { mutable.update { it.copy(active = false) } }
    }
    private fun checkNetwork(wifiOnly: Boolean) {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val caps = manager.getNetworkCapabilities(manager.activeNetwork)
        check(caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) { "Chybí internet. Po připojení obnovte stahování." }
        check(!wifiOnly || caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) { "Stahování čeká na Wi-Fi. Mobilní data lze povolit v nastavení." }
    }
}
