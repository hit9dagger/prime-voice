package cz.primevoice.translation

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import cz.primevoice.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class CloudSettings(val mode: NetworkMode = NetworkMode.AUTOMATIC, val consent: Boolean = false,
    val model: String = "", val configured: Boolean = false,
    val status: String = "Online překlad je vypnutý. Bez účtu a klíče fungují lokální režimy.")

/** Optional, documented Google Gemini OpenAI-compatible API. No key is shipped with the APK. */
class CloudTranslation(private val context: Context) {
    private val prefs = context.getSharedPreferences("cloud_settings", Context.MODE_PRIVATE)
    private val keyFile = AtomicFile(File(context.noBackupFilesDir, "cloud-key.enc"))
    private val guard = Any()
    @Volatile private var connection: HttpURLConnection? = null
    private var revision = 0
    private val mutable = MutableStateFlow(CloudSettings(
        mode = runCatching { NetworkMode.valueOf(prefs.getString("mode", "AUTOMATIC")!!) }.getOrDefault(NetworkMode.OFFLINE_ONLY),
        consent = prefs.getBoolean("consent", false), model = prefs.getString("model", "").orEmpty(),
        configured = keyFile.baseFile.exists() && !prefs.getString("model", "").isNullOrBlank()))
    val state = mutable.asStateFlow()
    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey("prime-cloud-key", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("prime-cloud-key", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    suspend fun configure(model: String, apiKey: String) = withContext(Dispatchers.IO) {
        require(model.matches(Regex("[A-Za-z0-9._/-]{1,100}"))) { "Zadejte platný identifikátor modelu poskytovatele." }
        require(apiKey.trim().length in 16..512 && apiKey.none { it == '\n' || it == '\r' }) { "Zadejte platný vlastní API klíč." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val encrypted = cipher.doFinal(apiKey.trim().toByteArray(Charsets.UTF_8))
        synchronized(guard) {
            connection?.disconnect(); revision++
            val out = keyFile.startWrite()
            try { out.write(cipher.iv.size); out.write(cipher.iv); out.write(encrypted); keyFile.finishWrite(out) }
            catch (e: Exception) { keyFile.failWrite(out); throw e }
            prefs.edit().putString("model", model).putBoolean("consent", false).apply()
            mutable.update { it.copy(model = model, configured = true, consent = false,
                status = "Klíč uložen šifrovaně v telefonu. Pro odesílání titulků ještě zapněte souhlas. Poskytovatel může účtovat poplatky nebo omezit kvótu.") }
        }
    }
    fun mode(mode: NetworkMode) = synchronized(guard) {
        revision++; connection?.disconnect()
        prefs.edit().putString("mode", mode.name).apply(); mutable.update { it.copy(mode = mode) }
    }
    fun consent(allowed: Boolean) = synchronized(guard) {
        revision++; connection?.disconnect()
        val enable = allowed && mutable.value.configured
        prefs.edit().putBoolean("consent", enable).apply(); mutable.update { it.copy(consent = enable) }
    }
    fun remove() = synchronized(guard) {
        revision++; connection?.disconnect(); keyFile.delete()
        prefs.edit().remove("model").putBoolean("consent", false).apply()
        mutable.update { it.copy(consent = false, configured = false, model = "", status = "Online klíč odstraněn.") }
    }
    fun cancel() { synchronized(guard) { revision++; connection?.disconnect() } }
    fun available(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val connected = cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val s = mutable.value
        return HybridPolicy.useCloud(s.mode, s.consent, s.configured, connected)
    }
    suspend fun translate(text: String, history: List<DialoguePair>): String = withContext(Dispatchers.IO) {
        val capturedRevision: Int; val configuration: CloudSettings
        val http = URL("https://generativelanguage.googleapis.com/v1beta/openai/chat/completions").openConnection() as HttpURLConnection
        synchronized(guard) {
            check(available()) { "Online překlad není povolen nebo chybí internet." }
            capturedRevision = revision; configuration = mutable.value; connection = http
        }
        try {
            val bytes = keyFile.readFully(); val ivLength = bytes[0].toInt() and 255
            require(ivLength in 12..16 && bytes.size > ivLength + 1)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes.copyOfRange(1, ivLength + 1)))
            }
            val key = cipher.doFinal(bytes.copyOfRange(ivLength + 1, bytes.size)).toString(Charsets.UTF_8)
            val body = JSONObject().put("model", configuration.model).put("temperature", 0)
                .put("max_tokens", 384).put("messages", JSONArray().put(JSONObject()
                    .put("role", "user").put("content", TranslationPrompt.build(text, history)))).toString().toByteArray(Charsets.UTF_8)
            http.requestMethod = "POST"; http.doOutput = true; http.instanceFollowRedirects = false
            http.connectTimeout = 4_000; http.readTimeout = 6_000
            http.setRequestProperty("Authorization", "Bearer $key")
            http.setRequestProperty("Content-Type", "application/json")
            http.setFixedLengthStreamingMode(body.size)
            synchronized(guard) { check(revision == capturedRevision && available()) { "Souhlas s online překladem se změnil." } }
            ensureActive(); http.outputStream.use { it.write(body) }
            check(http.responseCode == 200) { "Online překlad: HTTP ${http.responseCode}. Pokračuji lokálně." }
            val response = http.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
                while (true) { ensureActive(); val n = input.read(buffer); if (n < 0) break
                    check(out.size() + n <= 128 * 1024) { "Online odpověď je příliš velká." }; out.write(buffer, 0, n) }
                out.toString("UTF-8")
            }
            synchronized(guard) { check(revision == capturedRevision) { "Online překlad byl vypnut." } }
            val choice = JSONObject(response).getJSONArray("choices").getJSONObject(0)
            check(choice.optString("finish_reason") == "stop") { "Online model nedokončil celou větu." }
            val translated = choice.getJSONObject("message").getString("content").trim()
            check(LocalTranslationChecks.acceptable(text, translated)) { "Online model vrátil neplatný překlad." }
            mutable.update { it.copy(status = "Poslední online překlad dokončen službou Google Gemini.") }
            translated
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            // Never surface provider bodies, request text or credentials in logs/UI.
            mutable.update { it.copy(status = "Online služba není dostupná nebo požadavek odmítla. Používá se lokální překlad.") }
            throw IllegalStateException("Online překlad selhal; lokální záloha zůstává dostupná.")
        } finally { synchronized(guard) { if (connection === http) connection = null }; http.disconnect() }
    }
}
