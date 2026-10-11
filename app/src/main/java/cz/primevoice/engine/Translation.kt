package cz.primevoice.engine

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation as MlTranslation
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.TranslatorOptions
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/** Actual on-device translation; downloading only through explicit prepare(). */
class Translation : Closeable {
    private val translator = MlTranslation.getClient(TranslatorOptions.Builder()
        .setSourceLanguage(TranslateLanguage.ENGLISH).setTargetLanguage(TranslateLanguage.CZECH).build())
    private val closed = AtomicBoolean(false)
    private val operation = Mutex()
    suspend fun prepare() = operation.withLock {
        check(!closed.get()) { "Překladač byl ukončen." }
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        Unit
    }
    suspend fun ready(): Boolean = operation.withLock {
        check(!closed.get()) { "Překladač byl ukončen." }
        val czech = TranslateRemoteModel.Builder(TranslateLanguage.CZECH).build()
        RemoteModelManager.getInstance().isModelDownloaded(czech).await()
    }
    suspend fun translate(text: String): String = operation.withLock {
        check(!closed.get()) { "Překladač byl ukončen." }
        require(text.isNotBlank()) { "Text k překladu je prázdný." }
        translator.translate(text).await()
    }
    override fun close() { if (closed.compareAndSet(false, true)) translator.close() }
}
