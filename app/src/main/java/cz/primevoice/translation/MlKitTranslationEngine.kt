package cz.primevoice.translation

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.*
import cz.primevoice.core.DialogueContextManager
import cz.primevoice.core.Translator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

data class ModelState(val ready: Boolean = false, val downloading: Boolean = false, val message: String = "Kontroluji offline překladač…")
class MlKitTranslationEngine(private val context: DialogueContextManager,
                             private val names: () -> List<String>) : Translator {
    private val client = Translation.getClient(TranslatorOptions.Builder()
        .setSourceLanguage(TranslateLanguage.ENGLISH).setTargetLanguage(TranslateLanguage.CZECH).build())
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(ModelState())
    val state = mutable.asStateFlow()
    suspend fun checkModels() {
        try {
            val models = RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java).await()
            val ready = models.any { it.language == TranslateLanguage.CZECH }
            mutable.value = ModelState(ready, false, if (ready) "Offline překladač připraven" else "Stáhněte český překladový model (přibližně 30 MB).")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            mutable.value = ModelState(message = "Model se nepodařilo ověřit. Zkuste přípravu znovu.")
        }
    }
    suspend fun download(wifiOnly: Boolean) {
        if (mutable.value.downloading) return
        mutable.value = ModelState(downloading = true, message = "Připravuji offline překladač…")
        try {
            val conditions = DownloadConditions.Builder().apply { if (wifiOnly) requireWifi() }.build()
            withTimeout(180_000) { client.downloadModelIfNeeded(conditions).await() }
            mutable.value = ModelState(true, false, "Offline překladač připraven")
        } catch (e: TimeoutCancellationException) {
            mutable.value = ModelState(message = "Stažení trvá příliš dlouho. Ověřte připojení a zkuste znovu.")
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { mutable.value = ModelState(message = "Stažení selhalo: ${e.localizedMessage}") }
    }
    override suspend fun translate(text: String): String = withContext(Dispatchers.Default) {
        check(mutable.value.ready) { "Nejdříve stáhněte offline překladový model." }
        val prepared = context.prepare(text, names())
        // ML Kit translator is confined; queue workers still prepare while TTS plays.
        mutex.withLock { prepared.restore(client.translate(prepared.text).await()) }
    }
    fun close() = client.close()
}
