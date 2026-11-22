package cz.primevoice.translation

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.StatFs
import cz.primevoice.core.MIB
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class ModelTransfer(val active: Boolean = false, val downloaded: Long = 0, val total: Long = 0,
    val message: String = "Velké modely nejsou součástí APK.")

class LocalModelStore(private val context: Context) {
    private val directory = File(context.noBackupFilesDir, "language_models").apply {
        mkdirs(); listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
    }
    private val mutable = MutableStateFlow(ModelTransfer())
    val state = mutable.asStateFlow()
    fun file(spec: LocalModelSpec) = File(directory, spec.fileName)
    fun installed(spec: LocalModelSpec) = file(spec).length() == spec.bytes
    suspend fun verify(spec: LocalModelSpec): Boolean = withContext(Dispatchers.IO) {
        val source = file(spec)
        if (!installed(spec)) return@withContext false
        val digest = MessageDigest.getInstance("SHA-256")
        source.inputStream().use { input ->
            val bytes = ByteArray(128 * 1024)
            while (true) {
                ensureActive(); val n = input.read(bytes); if (n < 0) break
                digest.update(bytes, 0, n)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) } == spec.sha256
    }
    suspend fun download(spec: LocalModelSpec, wifiOnly: Boolean) = withContext(Dispatchers.IO) {
        requireNotNull(spec.url) { "Tento model stáhněte od vydavatele a importujte." }
        checkNetwork(wifiOnly)
        transfer(spec) {
            val connection = URL(spec.url).openConnection() as HttpURLConnection
            connection.connectTimeout = 20_000; connection.readTimeout = 20_000
            connection.instanceFollowRedirects = true
            try {
                check(connection.responseCode == 200) { "Stažení modelu: HTTP ${connection.responseCode}" }
                connection.inputStream.use { input -> copyVerified(spec, input) { checkNetwork(wifiOnly) } }
            } finally { connection.disconnect() }
        }
    }
    suspend fun import(spec: LocalModelSpec, uri: Uri) = withContext(Dispatchers.IO) {
        transfer(spec) {
            context.contentResolver.openInputStream(uri)?.use { copyVerified(spec, it) {} }
                ?: error("Soubor nelze otevřít.")
        }
    }
    private suspend fun transfer(spec: LocalModelSpec, block: suspend () -> Unit) {
        check(!mutable.value.active) { "Jiné stahování již běží." }
        check(StatFs(directory.path).availableBytes >= spec.bytes + 768 * MIB) { "Pro model a rezervu není dost místa." }
        mutable.value = ModelTransfer(true, 0, spec.bytes, "Připravuji ${spec.name}…")
        try {
            block()
            mutable.value = ModelTransfer(message = "Soubor ověřen. Nyní spusťte test kompatibility a překladu.")
        } catch (e: CancellationException) {
            mutable.value = ModelTransfer(message = "Příprava zrušena. Neúplný soubor byl odstraněn."); throw e
        } catch (e: Exception) {
            mutable.value = ModelTransfer(message = e.message ?: "Příprava modelu selhala."); throw e
        } finally { File(directory, spec.fileName + ".part").delete() }
    }
    private suspend fun copyVerified(spec: LocalModelSpec, input: InputStream, checkConnection: () -> Unit) {
        val part = File(directory, spec.fileName + ".part")
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L; var reported = 0L
        part.outputStream().buffered().use { output ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buffer); if (n < 0) break
                total += n; check(total <= spec.bytes) { "Soubor neodpovídá zvolenému modelu." }
                digest.update(buffer, 0, n); output.write(buffer, 0, n)
                if (total - reported >= MIB) {
                    checkConnection(); reported = total
                    mutable.value = mutable.value.copy(downloaded = total)
                    check(StatFs(directory.path).availableBytes > 256 * MIB) { "Úložiště se zaplnilo." }
                }
            }
        }
        check(total == spec.bytes && digest.digest().joinToString("") { "%02x".format(it) } == spec.sha256) {
            "Nesouhlasí velikost nebo SHA-256. Vyberte přesný model uvedený v aplikaci; soubor nebyl aktivován."
        }
        check(part.renameTo(file(spec))) { "Ověřený model nelze uložit." }
    }
    private fun checkNetwork(wifiOnly: Boolean) {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val caps = manager.getNetworkCapabilities(manager.activeNetwork)
        check(caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) { "Chybí připojení k internetu." }
        check(!wifiOnly || caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) { "Příprava vyžaduje Wi-Fi. Zapněte ji nebo zrušte omezení Wi-Fi." }
    }
}
